package dev.tripoder.hooklab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Persistent cache for application display presentation (label and icon) keyed by immutable generation. */
final class WorkbenchPresentations {
    private final File root;
    private final Map<String, Bundle> cache = new ConcurrentHashMap<>();

    WorkbenchPresentations(Context context) {
        root = new File(context.getFilesDir(), "presentations");
        load();
    }

    private void load() {
        if (!root.exists()) return;
        File[] files = root.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".meta")) {
                String generation = name.substring(0, name.length() - 5);
                try {
                    String label = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                    Bundle b = new Bundle();
                    b.putString("label", label);
                    b.putString("generation", generation);
                    File iconFile = new File(root, generation + ".png");
                    if (iconFile.exists()) {
                        b.putByteArray("icon", Files.readAllBytes(iconFile.toPath()));
                    }
                    cache.put(generation, b);
                } catch (Exception ignored) {
                }
            }
        }
    }

    Bundle get(String generation) {
        if (generation == null || generation.isEmpty()) return null;
        return cache.get(generation);
    }

    void put(String generation, Bundle value) {
        if (generation == null || generation.isEmpty() || value == null) return;
        cache.put(generation, value);
        new Thread(() -> {
            try {
                if (!root.exists()) root.mkdirs();
                String label = value.getString("label");
                if (label != null) {
                    File metaTmp = new File(root, generation + ".meta.tmp");
                    File metaFile = new File(root, generation + ".meta");
                    Files.writeString(metaTmp.toPath(), label, StandardCharsets.UTF_8);
                    metaTmp.renameTo(metaFile);
                }
                byte[] icon = value.getByteArray("icon");
                if (icon != null) {
                    File iconTmp = new File(root, generation + ".png.tmp");
                    File iconFile = new File(root, generation + ".png");
                    Files.write(iconTmp.toPath(), icon);
                    iconTmp.renameTo(iconFile);
                }
            } catch (Exception ignored) {
            }
        }, "save-presentation").start();
    }

    Drawable iconDrawable(Context context, Bundle value) {
        if (value == null) return null;
        byte[] icon = value.getByteArray("icon");
        if (icon == null || icon.length == 0) return null;
        try {
            Bitmap bmp = BitmapFactory.decodeByteArray(icon, 0, icon.length);
            if (bmp != null) return new BitmapDrawable(context.getResources(), bmp);
        } catch (Exception ignored) {
        }
        return null;
    }

    void prune(Set<String> activeGenerations) {
        if (activeGenerations == null || !root.exists()) return;
        cache.keySet().removeIf(gen -> !activeGenerations.contains(gen));
        new Thread(() -> {
            try {
                File[] files = root.listFiles();
                if (files == null) return;
                for (File file : files) {
                    String name = file.getName();
                    int dot = name.indexOf('.');
                    String gen = dot >= 0 ? name.substring(0, dot) : name;
                    if (!activeGenerations.contains(gen)) {
                        file.delete();
                    }
                }
            } catch (Exception ignored) {
            }
        }, "prune-presentations").start();
    }
}
