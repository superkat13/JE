# Sage audit of `llm-agent-factory`

## Scope and provenance

Confirmed source inspected: [`frontier-ai/llm-agent-factory`](https://huggingface.co/frontier-ai/llm-agent-factory), `main` commit `505aa09857889bc679f2b914e2c33527051c37a8`.

The inspected tree has no `LICENSE`, `LICENSE.txt`, `COPYING`, or `NOTICE` file. `README.md` declares `license: cc-by-sa-4.0` in Hugging Face front matter, and `gmas-main/pyproject.toml` declares `CC-BY-SA-4.0`. Those declarations are evidence of intended licensing, but the absence of complete license text and separate code/data provenance makes direct incorporation into an Android release legally ambiguous. This release therefore copies no upstream source code, agent record, prompt text, model, or trained weight. It uses a documented clean-room integration design informed by the public interfaces. Direct reuse remains blocked until the owner confirms licensing and attribution requirements.

## Exact inventory

- Main package: Python `>=3.12`; `accelerate`, `bitsandbytes`, `datasets`, `langchain`, `langchain-openai`, `peft`, `torch`, `transformers`, `trl`, `tqdm`, `rich`, `numpy`, `sentence-transformers`, `pydantic`, `openai`, and local `gmas-main`.
- Optional retrieval/deduplication: `faiss-cpu`, `scikit-learn`, `sentence-transformers`, Pydantic, and OpenAI-compatible generation.
- Bundled graph framework: `gmas-main`, Python `>=3.12`; `rustworkx`, Pydantic/settings, PyTorch, Loguru, sentence-transformers, LangGraph/LangChain, OpenAI, and Graphviz.
- Registries: `config/role_id.json` version 0.2 with 35 roles; `config/domain.json` version 0.2 with 692 domains; `config/tool.json` version 0.1 with 10 tool-name strings.
- Role IDs: `general`, `triage`, `router`, `supervisor`, `coordinator`, `memory_manager`, `reflector`, `planner`, `analyst`, `decision_maker`, `expert_advisor`, `critic`, `risk_assessor`, `verifier`, `fact_checker`, `researcher`, `retriever`, `librarian`, `synthesizer`, `tool_runner`, `executor`, `coder`, `debugger`, `architect`, `code_reviewer`, `devops`, `data_scientist`, `evaluator`, `summarizer`, `editor`, `translator`, `formatter`, `tutor`, `interviewer`, `safety_guard`.
- Tool names: `function_calling`, `web_search`, `remote_mcp_servers`, `file_search`, `image_generation`, `code_interpreter`, `computer_use`, `apply_patch`, `shell`, `vector_search`. They have no input/output schemas, risk levels, permission requirements, confirmation rules, or executable bindings.
- Datasets at the inspected revision: `agents_database` contains 692 JSON files / 16,176 agent rows / 10,013,362 bytes; `agents_sort_database` contains 693 files / 18,665 rows / 11,989,277 bytes; `task-agents_database` contains one JSONL file / 205,301 rows / 157,022,952 bytes.
- Model/training material: `alr-model/` contains adapter/trainer/optimizer artifacts; training and dataset scripts are under `script/`.

## Exact implementation file map

- Agent records and retrieval contracts: `retrieval/models.py`; generation schema/prompt assembly: `script/agent_generator.py`; graph profile: `gmas-main/rustworkx_framework/core/agent.py`.
- Role, domain, and tool registries: `config/role_id.json`, `config/domain.json`, and `config/tool.json`.
- Dataset loaders and validation boundary: `retrieval/data_loader.py`, `retrieval/config.py`, `retrieval/models.py`, and tests in `retrieval/tests/test_data_loader.py` / `retrieval/tests/test_config.py`.
- Embeddings, search, ranking, and cache handling: `retrieval/embedder.py`, `retrieval/retriever.py`, `retrieval/config.py`, and `retrieval/tests/test_retriever.py`.
- Retrieval-augmented generation and routing into an OpenAI-compatible model: `retrieval/rag.py`, `retrieval/rag_config.py`, `retrieval/rag_cli.py`, and `retrieval/tests/test_rag.py`.
- Meta-agent definitions for coordinator, router, tool runner, verifier, safety guard, memory manager, planner, evaluator, recovery handler, and state keeper: `script/add_meta_agents.py`.
- Graph construction and schemas: `gmas-main/rustworkx_framework/builder/graph_builder.py`, `gmas-main/rustworkx_framework/core/schema.py`, and `gmas-main/rustworkx_framework/core/graph.py`.
- Runtime coordination/routing: `gmas-main/rustworkx_framework/execution/runner.py` and `gmas-main/rustworkx_framework/execution/scheduler.py`.
- Upstream tool authority and implementations: `gmas-main/rustworkx_framework/tools/base.py`, `function_calling.py`, `file_search.py`, `code_interpreter.py`, `shell.py`, `web_search.py`, and `llm_integration.py` in that same directory.
- Memory and state: `gmas-main/rustworkx_framework/utils/memory.py` and `gmas-main/rustworkx_framework/utils/state_storage.py`.
- Dependency declarations: repository `pyproject.toml` / `requirements.txt` and `gmas-main/pyproject.toml` / `gmas-main/requirements.txt`.

## Component decision matrix

| Requested component | Exact upstream evidence | Dependencies | Android | Dell | Decision and integration path |
|---|---|---|---|---|---|
| AgentSpec | `retrieval/models.py::AgentSpec`; `gmas-main/.../core/agent.py::AgentProfile`; generator prompt schemas | Pydantic; graph variant also PyTorch | Poor | Good | Clean-room adapt. Forge schema adds role/domain, strict I/O schemas, provenance hash and trust state; downloaded specs remain data. |
| Agent JSONL datasets | `task-agents_database/agents_eng.jsonl`; per-domain JSON directories | JSON; generation provenance not encoded per row | Storage possible, generation/retrieval impractical | Good after curation | Reject direct bundling pending license/provenance review. Future Dell importer validates every row, records source revision, and quarantines untrusted tools. |
| Role registry | `config/role_id.json` v0.2, 35 strings | JSON | Good | Good | Adapt concepts. Forge accepts stable role IDs but does not derive authority from a role. |
| Domain registry | `config/domain.json` v0.2, 692 strings | JSON | Good | Good | Adapt on Dell after curation; not bundled now because database license/provenance is unresolved. |
| Tool registry | `config/tool.json` v0.1; `gmas-main/.../tools/base.py::ToolRegistry` | Pydantic and arbitrary Python `BaseTool` objects | Unsafe/incompatible | Technically compatible | Reject direct implementation. Sage's strict local schema and runner add every required security field and never accept executable bindings from agents. |
| Retrieval models | `retrieval/models.py::{AgentRecord, RetrievalResult, SearchQuery}` | Pydantic | Possible but adds runtime | Good | Adapt data contracts on Dell; validate score ranges and provenance. |
| Data loaders | `retrieval/data_loader.py` | Python/JSON/Pydantic | Poor | Good | Reject as-is: broad exception suppression silently skips malformed data and JSONL parsing can fall through into JSON parsing. Future importer is fail-closed with an error ledger. |
| Embedding/retrieval | `retrieval/embedder.py`, `retriever.py`, `config.py` | PyTorch, sentence-transformers; optional sklearn | Impractical for current APK | Good, GPU optional | Adapt architecture on Dell. Do not load untrusted cache because upstream uses `torch.load(..., weights_only=False)` for TF-IDF state. Use a non-executable vector format and content hashes. |
| Routing/ranking | `retrieval/retriever.py`; `gmas-main/.../execution/scheduler.py` | PyTorch/rustworkx/Pydantic | Impractical | Good | Adapt ranking concepts only. Policy/safety checks precede route execution; scores are recommendations, never grants. |
| RAG generation | `retrieval/rag.py`, `rag_config.py` | OpenAI-compatible API plus retrieval stack | Poor | Good | Adapt after retrieval hardening. Generated JSON is untrusted and must pass Sage's agent schema and owner review. |
| Coordinator | Prompt specification in `script/add_meta_agents.py`; graph execution in `gmas-main/.../execution/runner.py` | LLM stack / graph stack | Poor | Good | Adapt declarative role. Coordinator can enqueue proposals only; Tool Runner remains authoritative. |
| Router | Prompt specification in `script/add_meta_agents.py`; conditional routing in scheduler | Same | Poor | Good | Adapt after deterministic route-policy tests. |
| Tool runner | Prompt specification plus permissive `gmas-main/.../tools/base.py` execution | Arbitrary Python tool objects | Unsafe | Unsafe as-is | Reimplemented now as `sage_forge/tools.py`: allowlist, strict inputs, owner approval, platform/risk/timeout/concurrency checks, audit. |
| Verifier | Prompt specification in `script/add_meta_agents.py` | LLM | Poor | Good | Future clean-room agent. Verifier output cannot approve its own job. |
| Safety guard | Prompt specification; no security boundary in the dataset | LLM | Poor | Good | Reimplemented deterministic boundary now. LLM safety advice may be an additional signal, never the gate. |
| Memory manager | Prompt specification; `gmas-main/.../utils/memory.py` working/long-term/shared memory | PyTorch/Pydantic | Poor | Good | Adapt lifecycle concepts later; persist redacted Forge events in SQLite now. |
| Planner | Prompt specification in `script/add_meta_agents.py` | LLM | Poor | Good | Future clean-room proposal generator with allowlisted operation vocabulary. |
| Evaluator | Prompt specification in `script/add_meta_agents.py` | LLM/metrics | Poor | Good | Future ranking-only stage; cannot grant tools. |
| Recovery handler | Only requested in `script/add_meta_agents.py`; absent from `role_id.json` | LLM | Poor | Good | Treat as unimplemented upstream specification. Forge currently marks running jobs `interrupted` after restart; idempotent retry policy is deferred. |
| State keeper | Only requested in `script/add_meta_agents.py`; absent from `role_id.json`; generic file state in `gmas-main/.../utils/state_storage.py` | JSON filesystem | Possible | Good | Reject upstream file store for concurrent service state. Forge uses transactional SQLite jobs/logs/nonces/trust. |
| Graph schemas/validation | `gmas-main/.../core/schema.py`, `builder/graph_builder.py` | Pydantic, PyTorch, rustworkx | Poor | Good | Evaluate later after license clarity. Upstream allows extra fields in several models; repair execution needs `additionalProperties:false`. |
| Graph runner/scheduler | `gmas-main/.../execution/runner.py`, `scheduler.py` | Async Python, PyTorch, rustworkx | Poor | Good | Reject for trusted execution; potentially reuse conceptually for non-authoritative planning after tests and license review. |

## Confirmed defects and risks in the inspected revision

1. Schema drift: `retrieval/models.py::AgentSpec` omits `role_id`, domain and input/output schemas used in generator datasets and `gmas-main::AgentProfile`.
2. The loader suppresses broad exceptions and may silently omit bad records, preventing a complete audit trail.
3. `retrieval/retriever.py::_log` has an empty body, so its documented status output is suppressed.
4. TF-IDF cache restoration uses `torch.load(..., weights_only=False)`. A downloaded or tampered cache must be treated as executable/untrusted serialization.
5. Upstream tool registry membership is enough to execute a Python `BaseTool`; it lacks risk, confirmation, permission, network-scope, egress, timeout and concurrency policy.
6. Tool calls parsed from model text are name/arguments only. Agent-provided tool names are not a security boundary.
7. `recovery_handler` and `state_keeper` are present in a generation prompt but absent from the canonical role registry; they are not implementations.

## Components adapted in this release

The following are clean-room adaptations, not source copies: declarative agent records, role/domain separation, retrieval result ranking as future non-authoritative advice, a coordinator/router/verifier pipeline concept, and separation of Tool Runner, Safety Guard, memory and state. Their Sage implementations are `sage_forge/agents.py`, `sage_forge/tools.py`, `sage_forge/store.py`, and the schemas in `sage_forge/schemas/`.
