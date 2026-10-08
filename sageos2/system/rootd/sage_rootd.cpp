#include <algorithm>
#include <atomic>
#include <chrono>
#include <cctype>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <fstream>
#include <map>
#include <optional>
#include <poll.h>
#include <signal.h>
#include <sstream>
#include <string>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#include <utility>
#include <vector>

namespace {

constexpr const char* kProtocol = "SAGE_ROOTD 1";
constexpr const char* kVersion = "2.0.0";
constexpr const char* kAllowedPeerPrefix = "u:r:sage_app:s0";
constexpr size_t kMaxRequestBytes = 64 * 1024;
constexpr size_t kMaxLineBytes = 8 * 1024;
constexpr size_t kMaxCaptureBytes = 32 * 1024;
constexpr long kMaxTimeoutMs = 300000;
constexpr const char* kAuditPath = "/data/misc/sage/rootd-audit.log";
constexpr const char* kAuditOldPath = "/data/misc/sage/rootd-audit.log.1";
constexpr off_t kAuditRotateBytes = 1024 * 1024;

std::atomic<uint64_t> g_audit_counter{0};

struct Request {
    std::string id;
    std::string op;
    std::map<std::string, std::vector<std::string>> fields;
    std::vector<std::string> args;
    std::vector<std::pair<std::string, std::string>> env;
};

struct Response {
    std::string id;
    bool success = false;
    std::string code;
    std::string detail;
    std::string audit_id;
    std::optional<int> exit_code;
    std::string stdout_text;
    std::string stderr_text;
};

struct ProcessResult {
    int exit_code = -1;
    bool timed_out = false;
    std::string stdout_text;
    std::string stderr_text;
};

bool WriteAll(int fd, const std::string& data) {
    size_t offset = 0;
    while (offset < data.size()) {
        const ssize_t n = TEMP_FAILURE_RETRY(write(fd, data.data() + offset, data.size() - offset));
        if (n <= 0) return false;
        offset += static_cast<size_t>(n);
    }
    return true;
}

bool ReadLine(int fd, std::string* out, size_t* total) {
    out->clear();
    while (out->size() < kMaxLineBytes && *total < kMaxRequestBytes) {
        char c = 0;
        const ssize_t n = TEMP_FAILURE_RETRY(read(fd, &c, 1));
        if (n <= 0) return false;
        ++(*total);
        if (c == '\n') return true;
        if (c != '\r') out->push_back(c);
    }
    return false;
}

int HexNibble(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return 10 + (c - 'a');
    if (c >= 'A' && c <= 'F') return 10 + (c - 'A');
    return -1;
}

std::optional<std::string> HexDecode(const std::string& hex) {
    if (hex.size() % 2 != 0 || hex.size() > kMaxRequestBytes * 2) return std::nullopt;
    std::string out;
    out.reserve(hex.size() / 2);
    for (size_t i = 0; i < hex.size(); i += 2) {
        const int hi = HexNibble(hex[i]);
        const int lo = HexNibble(hex[i + 1]);
        if (hi < 0 || lo < 0) return std::nullopt;
        out.push_back(static_cast<char>((hi << 4) | lo));
    }
    if (out.find('\0') != std::string::npos) return std::nullopt;
    return out;
}

std::string HexEncode(const std::string& text) {
    static constexpr char kHex[] = "0123456789abcdef";
    std::string out;
    out.reserve(text.size() * 2);
    for (unsigned char c : text) {
        out.push_back(kHex[c >> 4]);
        out.push_back(kHex[c & 0x0f]);
    }
    return out;
}

std::vector<std::string> Split(const std::string& line) {
    std::istringstream in(line);
    std::vector<std::string> parts;
    std::string part;
    while (in >> part) parts.push_back(part);
    return parts;
}

bool IsToken(const std::string& value, size_t max_len = 128) {
    if (value.empty() || value.size() > max_len) return false;
    return std::all_of(value.begin(), value.end(), [](unsigned char c) {
        return std::isalnum(c) || c == '_' || c == '-' || c == '.' || c == ':' || c == '/';
    });
}

bool IsPackageName(const std::string& value) {
    if (value.empty() || value.size() > 255) return false;
    return std::all_of(value.begin(), value.end(), [](unsigned char c) {
        return std::isalnum(c) || c == '_' || c == '.';
    });
}

bool IsAbsolutePath(const std::string& value) {
    return !value.empty() && value.size() <= 4096 && value.front() == '/' && value.find('\0') == std::string::npos;
}

std::optional<long> ParseLong(const std::string& text, long min_value, long max_value) {
    if (text.empty()) return std::nullopt;
    char* end = nullptr;
    errno = 0;
    const long value = strtol(text.c_str(), &end, 10);
    if (errno != 0 || end == nullptr || *end != '\0' || value < min_value || value > max_value) return std::nullopt;
    return value;
}

std::optional<std::string> FieldOne(const Request& request, const std::string& key) {
    const auto it = request.fields.find(key);
    if (it == request.fields.end() || it->second.size() != 1) return std::nullopt;
    return it->second.front();
}

bool ParseRequest(int fd, Request* request, std::string* error) {
    size_t total = 0;
    std::string line;
    if (!ReadLine(fd, &line, &total) || line != kProtocol) {
        *error = "bad protocol header";
        return false;
    }
    while (ReadLine(fd, &line, &total)) {
        if (line == "END") break;
        const auto parts = Split(line);
        if (parts.empty()) continue;
        if (parts[0] == "id" && parts.size() == 2) {
            auto decoded = HexDecode(parts[1]);
            if (!decoded || decoded->empty() || decoded->size() > 128) { *error = "invalid request id"; return false; }
            request->id = *decoded;
        } else if (parts[0] == "op" && parts.size() == 2) {
            if (!IsToken(parts[1], 64)) { *error = "invalid operation token"; return false; }
            request->op = parts[1];
        } else if (parts[0] == "f" && parts.size() == 3) {
            if (!IsToken(parts[1], 64)) { *error = "invalid field key"; return false; }
            auto decoded = HexDecode(parts[2]);
            if (!decoded) { *error = "invalid field encoding"; return false; }
            request->fields[parts[1]].push_back(*decoded);
        } else if (parts[0] == "a" && parts.size() == 2) {
            auto decoded = HexDecode(parts[1]);
            if (!decoded) { *error = "invalid argument encoding"; return false; }
            request->args.push_back(*decoded);
            if (request->args.size() > 128) { *error = "too many arguments"; return false; }
        } else if (parts[0] == "e" && parts.size() == 3) {
            auto key = HexDecode(parts[1]);
            auto value = HexDecode(parts[2]);
            if (!key || !value || key->empty() || key->size() > 256 || key->find('=') != std::string::npos) {
                *error = "invalid environment entry";
                return false;
            }
            request->env.emplace_back(*key, *value);
            if (request->env.size() > 64) { *error = "too many environment entries"; return false; }
        } else {
            *error = "unknown protocol line";
            return false;
        }
    }
    if (total >= kMaxRequestBytes) { *error = "request too large"; return false; }
    if (request->id.empty() || request->op.empty()) { *error = "missing id or operation"; return false; }
    return true;
}

std::string EncodeResponse(const Response& response) {
    std::ostringstream out;
    out << kProtocol << '\n';
    out << "id " << HexEncode(response.id) << '\n';
    out << "success " << (response.success ? "1" : "0") << '\n';
    out << "code " << HexEncode(response.code) << '\n';
    out << "detail " << HexEncode(response.detail) << '\n';
    if (!response.audit_id.empty()) out << "audit " << HexEncode(response.audit_id) << '\n';
    if (response.exit_code.has_value()) out << "exit " << *response.exit_code << '\n';
    if (!response.stdout_text.empty()) out << "stdout " << HexEncode(response.stdout_text) << '\n';
    if (!response.stderr_text.empty()) out << "stderr " << HexEncode(response.stderr_text) << '\n';
    out << "END\n";
    return out.str();
}

std::string PeerSecurityContext(int fd) {
#ifdef SO_PEERSEC
    std::vector<char> buffer(512, 0);
    socklen_t length = static_cast<socklen_t>(buffer.size());
    if (getsockopt(fd, SOL_SOCKET, SO_PEERSEC, buffer.data(), &length) != 0) return {};
    while (length > 0 && buffer[length - 1] == '\0') --length;
    return std::string(buffer.data(), length);
#else
    (void)fd;
    return {};
#endif
}

bool AuthenticatePeer(int fd, uid_t* uid, std::string* detail) {
    struct ucred cred {};
    socklen_t cred_len = sizeof(cred);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cred, &cred_len) != 0) {
        *detail = "SO_PEERCRED unavailable";
        return false;
    }
    const std::string peer = PeerSecurityContext(fd);
    if (peer.rfind(kAllowedPeerPrefix, 0) != 0) {
        *detail = "caller SELinux domain is not sage_app";
        return false;
    }
    *uid = cred.uid;
    return true;
}

void AppendCapture(std::string* target, const char* data, ssize_t count) {
    if (count <= 0 || target->size() >= kMaxCaptureBytes) return;
    const size_t remaining = kMaxCaptureBytes - target->size();
    target->append(data, std::min(remaining, static_cast<size_t>(count)));
}

void DrainFd(int fd, std::string* target) {
    char buffer[4096];
    while (true) {
        const ssize_t n = read(fd, buffer, sizeof(buffer));
        if (n > 0) AppendCapture(target, buffer, n);
        else break;
    }
}

ProcessResult RunProcess(const std::string& executable,
                         const std::vector<std::string>& args,
                         const std::vector<std::pair<std::string, std::string>>& env,
                         const std::optional<std::string>& cwd,
                         long timeout_ms) {
    ProcessResult result;
    int out_pipe[2] = {-1, -1};
    int err_pipe[2] = {-1, -1};
    if (pipe2(out_pipe, O_CLOEXEC) != 0 || pipe2(err_pipe, O_CLOEXEC) != 0) {
        result.stderr_text = std::string("pipe failed: ") + strerror(errno);
        return result;
    }
    const pid_t pid = fork();
    if (pid < 0) {
        result.stderr_text = std::string("fork failed: ") + strerror(errno);
        close(out_pipe[0]); close(out_pipe[1]); close(err_pipe[0]); close(err_pipe[1]);
        return result;
    }
    if (pid == 0) {
        dup2(out_pipe[1], STDOUT_FILENO);
        dup2(err_pipe[1], STDERR_FILENO);
        close(out_pipe[0]); close(out_pipe[1]); close(err_pipe[0]); close(err_pipe[1]);
        if (cwd && chdir(cwd->c_str()) != 0) {
            dprintf(STDERR_FILENO, "chdir failed: %s\n", strerror(errno));
            _exit(126);
        }
        for (const auto& [key, value] : env) setenv(key.c_str(), value.c_str(), 1);
        std::vector<char*> argv;
        argv.reserve(args.size() + 2);
        argv.push_back(const_cast<char*>(executable.c_str()));
        for (const auto& arg : args) argv.push_back(const_cast<char*>(arg.c_str()));
        argv.push_back(nullptr);
        execv(executable.c_str(), argv.data());
        dprintf(STDERR_FILENO, "execv failed: %s\n", strerror(errno));
        _exit(127);
    }

    close(out_pipe[1]); close(err_pipe[1]);
    fcntl(out_pipe[0], F_SETFL, fcntl(out_pipe[0], F_GETFL) | O_NONBLOCK);
    fcntl(err_pipe[0], F_SETFL, fcntl(err_pipe[0], F_GETFL) | O_NONBLOCK);
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);
    int status = 0;
    while (true) {
        struct pollfd fds[2] = {{out_pipe[0], POLLIN, 0}, {err_pipe[0], POLLIN, 0}};
        poll(fds, 2, 50);
        DrainFd(out_pipe[0], &result.stdout_text);
        DrainFd(err_pipe[0], &result.stderr_text);
        const pid_t waited = waitpid(pid, &status, WNOHANG);
        if (waited == pid) break;
        if (waited < 0) break;
        if (std::chrono::steady_clock::now() >= deadline) {
            result.timed_out = true;
            kill(pid, SIGKILL);
            waitpid(pid, &status, 0);
            break;
        }
    }
    DrainFd(out_pipe[0], &result.stdout_text);
    DrainFd(err_pipe[0], &result.stderr_text);
    close(out_pipe[0]); close(err_pipe[0]);
    if (WIFEXITED(status)) result.exit_code = WEXITSTATUS(status);
    else if (WIFSIGNALED(status)) result.exit_code = 128 + WTERMSIG(status);
    return result;
}

Response FromProcess(const std::string& request_id, const ProcessResult& process) {
    Response response;
    response.id = request_id;
    response.exit_code = process.exit_code;
    response.stdout_text = process.stdout_text;
    response.stderr_text = process.stderr_text;
    if (process.timed_out) {
        response.success = false;
        response.code = "TIMEOUT";
        response.detail = "privileged process exceeded timeout";
    } else if (process.exit_code == 0) {
        response.success = true;
        response.code = "OK";
        response.detail = "operation completed";
    } else {
        response.success = false;
        response.code = "PROCESS_EXIT";
        response.detail = "privileged process returned non-zero exit status";
    }
    return response;
}

std::string MakeAuditId() {
    const auto now = std::chrono::system_clock::now().time_since_epoch();
    const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(now).count();
    return std::to_string(ms) + "-" + std::to_string(getpid()) + "-" + std::to_string(++g_audit_counter);
}

void RotateAuditIfNeeded() {
    struct stat st {};
    if (stat(kAuditPath, &st) == 0 && st.st_size >= kAuditRotateBytes) {
        unlink(kAuditOldPath);
        rename(kAuditPath, kAuditOldPath);
    }
}

void Audit(uid_t uid, const Request& request, const Response& response, long duration_ms) {
    RotateAuditIfNeeded();
    std::ofstream out(kAuditPath, std::ios::app);
    if (!out) return;
    out << response.audit_id << '\t'
        << "uid=" << uid << '\t'
        << "op=" << request.op << '\t'
        << "success=" << (response.success ? 1 : 0) << '\t'
        << "code=" << response.code << '\t'
        << "duration_ms=" << duration_ms << '\n';
}

Response ErrorResponse(const std::string& id, const std::string& code, const std::string& detail) {
    Response response;
    response.id = id;
    response.success = false;
    response.code = code;
    response.detail = detail;
    return response;
}

Response ExecuteRequest(const Request& request) {
    if (request.op == "HEALTH") {
        Response response;
        response.id = request.id;
        response.success = true;
        response.code = "OK";
        response.detail = std::string("sage-rootd ") + kVersion;
        return response;
    }

    if (request.op == "INSTALL_PACKAGE") {
        const auto path = FieldOne(request, "path");
        const auto replace = FieldOne(request, "replace");
        if (!path || !replace || !IsAbsolutePath(*path) || (*replace != "0" && *replace != "1"))
            return ErrorResponse(request.id, "INVALID_ARGUMENT", "invalid package install fields");
        std::vector<std::string> args = {"package", "install"};
        if (*replace == "1") args.push_back("-r");
        args.push_back(*path);
        return FromProcess(request.id, RunProcess("/system/bin/cmd", args, {}, std::nullopt, 120000));
    }

    if (request.op == "UNINSTALL_PACKAGE") {
        const auto package_name = FieldOne(request, "package");
        const auto keep_data = FieldOne(request, "keep_data");
        if (!package_name || !keep_data || !IsPackageName(*package_name) || (*keep_data != "0" && *keep_data != "1"))
            return ErrorResponse(request.id, "INVALID_ARGUMENT", "invalid package uninstall fields");
        std::vector<std::string> args = {"package", "uninstall"};
        if (*keep_data == "1") args.push_back("-k");
        args.push_back(*package_name);
        return FromProcess(request.id, RunProcess("/system/bin/cmd", args, {}, std::nullopt, 120000));
    }

    if (request.op == "SET_PACKAGE_ENABLED") {
        const auto package_name = FieldOne(request, "package");
        const auto enabled = FieldOne(request, "enabled");
        if (!package_name || !enabled || !IsPackageName(*package_name) || (*enabled != "0" && *enabled != "1"))
            return ErrorResponse(request.id, "INVALID_ARGUMENT", "invalid package state fields");
        std::vector<std::string> args = {*enabled == "1" ? "enable" : "disable-user", "--user", "0", *package_name};
        return FromProcess(request.id, RunProcess("/system/bin/pm", args, {}, std::nullopt, 30000));
    }

    if (request.op == "WRITE_SETTING") {
        const auto name_space = FieldOne(request, "namespace");
        const auto key = FieldOne(request, "key");
        const auto has_value = FieldOne(request, "has_value");
        if (!name_space || !key || !has_value || !IsToken(*name_space, 16) || !IsToken(*key, 256) || (*has_value != "0" && *has_value != "1"))
            return ErrorResponse(request.id, "INVALID_ARGUMENT", "invalid settings fields");
        if (*name_space != "system" && *name_space != "secure" && *name_space != "global")
            return ErrorResponse(request.id, "INVALID_ARGUMENT", "unknown settings namespace");
        std::vector<std::string> args;
        if (*has_value == "1") {
            const auto value = FieldOne(request, "value");
            if (!value || value->size() > 8192) return ErrorResponse(request.id, "INVALID_ARGUMENT", "invalid settings value");
            args = {"put", *name_space, *key, *value};
        } else {
            args = {"delete", *name_space, *key};
        }
        return FromProcess(request.id, RunProcess("/system/bin/settings", args, {}, std::nullopt, 30000));
    }

    if (request.op == "CHOWN") {
        const auto path = FieldOne(request, "path");
        const auto uid_text = FieldOne(request, "uid");
        const auto gid_text = FieldOne(request, "gid");
        const auto uid = uid_text ? ParseLong(*uid_text, 0, 1000000) : std::nullopt;
        const auto gid = gid_text ? ParseLong(*gid_text, 0, 1000000) : std::nullopt;
        if (!path || !uid || !gid || !IsAbsolutePath(*path)) return ErrorResponse(request.id, "INVALID_ARGUMENT", "invalid chown fields");
        if (chown(path->c_str(), static_cast<uid_t>(*uid), static_cast<gid_t>(*gid)) != 0)
            return ErrorResponse(request.id, "OS_ERROR", std::string("chown failed: ") + strerror(errno));
        Response response{request.id, true, "OK", "ownership changed"};
        return response;
    }

    if (request.op == "CHMOD") {
        const auto path = FieldOne(request, "path");
        const auto mode_text = FieldOne(request, "mode");
        const auto mode = mode_text ? ParseLong(*mode_text, 0, 07777) : std::nullopt;
        if (!path || !mode || !IsAbsolutePath(*path)) return ErrorResponse(request.id, "INVALID_ARGUMENT", "invalid chmod fields");
        if (chmod(path->c_str(), static_cast<mode_t>(*mode)) != 0)
            return ErrorResponse(request.id, "OS_ERROR", std::string("chmod failed: ") + strerror(errno));
        Response response{request.id, true, "OK", "mode changed"};
        return response;
    }

    if (request.op == "RESTART_SERVICE") {
        const auto service = FieldOne(request, "service");
        if (!service || !IsToken(*service, 128)) return ErrorResponse(request.id, "INVALID_ARGUMENT", "invalid service name");
        return FromProcess(request.id, RunProcess("/system/bin/setprop", {"ctl.restart", *service}, {}, std::nullopt, 30000));
    }

    if (request.op == "POWER") {
        const auto action = FieldOne(request, "action");
        if (!action) return ErrorResponse(request.id, "INVALID_ARGUMENT", "missing power action");
        if (*action == "REBOOT") return FromProcess(request.id, RunProcess("/system/bin/reboot", {}, {}, std::nullopt, 30000));
        if (*action == "SHUTDOWN") return FromProcess(request.id, RunProcess("/system/bin/reboot", {"-p"}, {}, std::nullopt, 30000));
        if (*action == "REBOOT_RECOVERY") return FromProcess(request.id, RunProcess("/system/bin/reboot", {"recovery"}, {}, std::nullopt, 30000));
        return ErrorResponse(request.id, "INVALID_ARGUMENT", "unknown power action");
    }

    if (request.op == "EXEC") {
        const auto executable = FieldOne(request, "executable");
        const auto cwd_field = FieldOne(request, "cwd");
        const auto timeout_field = FieldOne(request, "timeout_ms");
        if (!executable || !timeout_field || !IsAbsolutePath(*executable))
            return ErrorResponse(request.id, "INVALID_ARGUMENT", "invalid executable or timeout");
        const auto timeout = ParseLong(*timeout_field, 1, kMaxTimeoutMs);
        if (!timeout) return ErrorResponse(request.id, "INVALID_ARGUMENT", "timeout outside supported range");
        std::optional<std::string> cwd;
        if (cwd_field && !cwd_field->empty()) {
            if (!IsAbsolutePath(*cwd_field)) return ErrorResponse(request.id, "INVALID_ARGUMENT", "working directory must be absolute");
            cwd = *cwd_field;
        }
        return FromProcess(request.id, RunProcess(*executable, request.args, request.env, cwd, *timeout));
    }

    return ErrorResponse(request.id, "UNKNOWN_OPERATION", "operation is not part of protocol v1");
}

void HandleClient(int client_fd) {
    uid_t uid = static_cast<uid_t>(-1);
    std::string auth_error;
    if (!AuthenticatePeer(client_fd, &uid, &auth_error)) {
        Response response = ErrorResponse("unauthenticated", "UNAUTHENTICATED", auth_error);
        WriteAll(client_fd, EncodeResponse(response));
        return;
    }

    Request request;
    std::string parse_error;
    if (!ParseRequest(client_fd, &request, &parse_error)) {
        Response response = ErrorResponse(request.id.empty() ? "malformed" : request.id, "MALFORMED_REQUEST", parse_error);
        WriteAll(client_fd, EncodeResponse(response));
        return;
    }

    const auto started = std::chrono::steady_clock::now();
    Response response = ExecuteRequest(request);
    response.audit_id = MakeAuditId();
    const auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - started).count();
    Audit(uid, request, response, duration);
    WriteAll(client_fd, EncodeResponse(response));
}

}  // namespace

int main() {
    signal(SIGPIPE, SIG_IGN);
    const char* socket_env = getenv("ANDROID_SOCKET_sage_rootd");
    if (socket_env == nullptr) return 2;
    const int server_fd = atoi(socket_env);
    if (server_fd <= 0) return 3;
    if (listen(server_fd, 4) != 0) return 4;

    while (true) {
        const int client_fd = accept4(server_fd, nullptr, nullptr, SOCK_CLOEXEC);
        if (client_fd < 0) {
            if (errno == EINTR) continue;
            return 5;
        }
        HandleClient(client_fd);
        close(client_fd);
    }
}
