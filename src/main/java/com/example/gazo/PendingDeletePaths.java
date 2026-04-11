package com.example.gazo;

import com.example.gazo.vault.GazoVaultService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 重複整理などで「あとでまとめて削除」する画像パスの予約集合。
 */
public final class PendingDeletePaths {
    private final Set<Path> paths = Collections.synchronizedSet(new LinkedHashSet<>());

    public boolean contains(Path path) {
        if (path == null) {
            return false;
        }
        synchronized (paths) {
            return paths.contains(path);
        }
    }

    public void addAll(List<Path> targets) {
        if (targets == null || targets.isEmpty()) {
            return;
        }
        synchronized (paths) {
            paths.addAll(targets);
        }
    }

    /** 予約をすべて実削除し、成功したものだけ集合から外す。 */
    public void flushAll(GazoVaultService vault, boolean showUiError) {
        if (vault == null) {
            return;
        }
        List<Path> targets;
        synchronized (paths) {
            if (paths.isEmpty()) {
                return;
            }
            targets = new ArrayList<>(paths);
        }
        List<String> failed = new ArrayList<>();
        for (Path p : targets) {
            try {
                vault.deleteImage(p);
                synchronized (paths) {
                    paths.remove(p);
                }
            } catch (Exception ex) {
                failed.add(p.getFileName() + ": " + ex.getMessage());
            }
        }
        if (showUiError && !failed.isEmpty()) {
            GazoFx.showError("削除エラー", String.join("\n", failed));
        }
    }
}
