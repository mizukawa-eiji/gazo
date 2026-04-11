package com.example.gazo;

import com.example.gazo.vault.GazoVaultService;
import javafx.scene.control.TextInputDialog;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 画像一覧のタグ編集・チェック画像への一括タグ追加／削除。
 */
public final class GalleryListTagActions {
    public record Host(
            Supplier<GazoVaultService> vault,
            Set<Path> listCheckedSelection,
            Runnable refreshTagFilterOptions,
            Runnable refreshGallery) {}

    private final Host host;

    public GalleryListTagActions(Host host) {
        this.host = host;
    }

    public void editTags(Path imagePath) {
        GazoVaultService vault = host.vault().get();
        String current = "";
        try {
            current = String.join(", ", vault.getTags(imagePath));
        } catch (IOException e) {
            GazoFx.showError("タグ読み込みエラー", e.getMessage());
            return;
        }

        TextInputDialog dialog = new TextInputDialog(current);
        dialog.setTitle("タグ編集");
        dialog.setHeaderText(imagePath.getFileName().toString());
        dialog.setContentText("タグ（カンマ区切り）:");
        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) {
            return;
        }

        Set<String> tags = UserTags.parseCommaSeparated(result.get());
        try {
            vault.setTags(imagePath, tags);
            host.refreshTagFilterOptions().run();
            host.refreshGallery().run();
        } catch (IOException e) {
            GazoFx.showError("タグ保存エラー", e.getMessage());
        }
    }

    public void addTagsToCanvasSelection() {
        if (host.listCheckedSelection().isEmpty()) {
            GazoFx.showWarn("タグ一括追加", "先に画像をチェックしてください。");
            return;
        }
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("タグ一括追加");
        dialog.setHeaderText("チェック済み画像 " + host.listCheckedSelection().size() + " 件にタグを追加");
        dialog.setContentText("追加するタグ（カンマ区切り）:");
        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) {
            return;
        }
        Set<String> addTags = UserTags.parseCommaSeparated(result.get());
        if (addTags.isEmpty()) {
            return;
        }
        try {
            GazoVaultService vault = host.vault().get();
            for (Path p : host.listCheckedSelection()) {
                Set<String> tags = new LinkedHashSet<>(vault.getTags(p));
                tags.addAll(addTags);
                vault.setTags(p, tags);
            }
            host.refreshTagFilterOptions().run();
            host.refreshGallery().run();
            GazoFx.showWarn("タグ一括追加", addTags.size() + " 個のタグを追加しました。");
        } catch (IOException e) {
            GazoFx.showError("タグ一括追加エラー", e.getMessage());
        }
    }

    public void removeTagsFromCanvasSelection() {
        if (host.listCheckedSelection().isEmpty()) {
            GazoFx.showWarn("タグ一括削除", "先に画像をチェックしてください。");
            return;
        }
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("タグ一括削除");
        dialog.setHeaderText("チェック済み画像 " + host.listCheckedSelection().size() + " 件からタグを削除");
        dialog.setContentText("削除するタグ（カンマ区切り）:");
        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) {
            return;
        }
        Set<String> removeTags = UserTags.parseCommaSeparated(result.get());
        if (removeTags.isEmpty()) {
            return;
        }
        try {
            GazoVaultService vault = host.vault().get();
            for (Path p : host.listCheckedSelection()) {
                Set<String> tags = new LinkedHashSet<>(vault.getTags(p));
                tags.removeAll(removeTags);
                vault.setTags(p, tags);
            }
            host.refreshTagFilterOptions().run();
            host.refreshGallery().run();
            GazoFx.showWarn("タグ一括削除", removeTags.size() + " 個のタグを削除しました。");
        } catch (IOException e) {
            GazoFx.showError("タグ一括削除エラー", e.getMessage());
        }
    }
}
