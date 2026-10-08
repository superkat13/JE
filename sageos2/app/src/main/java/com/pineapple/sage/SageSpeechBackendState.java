package com.pineapple.sage;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * Read-only readiness check for the local command-speech model installed by Sage 1.31-1.33.
 * The stable package keeps this private directory across an in-place SageOS 2 update.
 */
public final class SageSpeechBackendState {
    public static final String SHERPA_VERSION = "1.13.7";
    public static final String MODEL_ID =
            "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17";

    private SageSpeechBackendState() { }

    public static boolean sherpaJavaApiPresent(Context context) {
        if (context == null) return false;
        try {
            Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer", false,
                    context.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean sherpaNativePresent(Context context) {
        if (context == null || context.getApplicationInfo() == null) return false;
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        if (nativeDir != null) {
            File directory = new File(nativeDir);
            if (new File(directory, "libsherpa-onnx-jni.so").isFile()
                    && new File(directory, "libonnxruntime.so").isFile()) return true;
        }
        return packagedNativePresent(context, "libsherpa-onnx-jni.so")
                && packagedNativePresent(context, "libonnxruntime.so");
    }

    private static boolean packagedNativePresent(Context context, String library) {
        List<String> apks = new ArrayList<>();
        if (context.getApplicationInfo().sourceDir != null)
            apks.add(context.getApplicationInfo().sourceDir);
        if (context.getApplicationInfo().splitSourceDirs != null) {
            for (String split : context.getApplicationInfo().splitSourceDirs)
                if (split != null) apks.add(split);
        }
        for (String apk : apks) {
            try (ZipFile zip = new ZipFile(apk)) {
                for (String abi : Build.SUPPORTED_ABIS) {
                    if (zip.getEntry("lib/" + abi + "/" + library) != null) return true;
                }
            } catch (Exception ignored) { }
        }
        return false;
    }

    public static File modelDirectory(Context context) {
        return new File(new File(new File(context.getFilesDir(), "speech"), "sherpa"),
                MODEL_ID);
    }

    public static boolean sherpaModelPresent(Context context) {
        if (context == null) return false;
        File dir = modelDirectory(context);
        return new File(dir, "verified.properties").isFile()
                && exactFile(dir, "tokens.txt", 5_048L)
                && exactFile(dir, "encoder-epoch-99-avg-1.int8.onnx", 42_845_182L)
                && exactFile(dir, "decoder-epoch-99-avg-1.onnx", 2_092_272L)
                && exactFile(dir, "joiner-epoch-99-avg-1.int8.onnx", 259_572L);
    }

    private static boolean exactFile(File dir, String name, long bytes) {
        File value = new File(dir, name);
        return value.isFile() && value.length() == bytes;
    }

    public static boolean sherpaReady(Context context) {
        return sherpaJavaApiPresent(context)
                && sherpaNativePresent(context)
                && sherpaModelPresent(context);
    }

    public static String readinessDetail(Context context) {
        boolean api = sherpaJavaApiPresent(context);
        boolean nativeReady = sherpaNativePresent(context);
        boolean model = sherpaModelPresent(context);
        if (api && nativeReady && model) {
            return "local sherpa command speech ready";
        }
        return "local sherpa command speech unavailable"
                + " [java_api=" + api
                + ",native=" + nativeReady
                + ",model=" + model + "]";
    }
}
