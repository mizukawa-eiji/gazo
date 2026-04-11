package com.example.gazo;

import com.example.gazo.vault.GazoVaultService;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 画像一覧のチェック済みファイルを Vault から削除する（確認ダイアログ付き）。
 */
public final class GalleryCheckedImagesDelete {
    private GalleryCheckedImagesDelete() {}

    public static void confirmAndDelete(
            Stage owner,
            GazoVaultService vault,
            Set<Path> listCheckedSelection,
            Set<Path> canvasSelection,
            Predicate<Path> isPendingDelete,
            Runnable afterDeleted) {
        if (listCheckedSelection.isEmpty()) {
            GazoFx.showWarn("画像を削除", "先に画像をチェックしてください。");
            return;
        }
        List<Path> targets = new ArrayList<>();
        for (Path p : new ArrayList<>(listCheckedSelection)) {
            if (!isPendingDelete.test(p)) {
                targets.add(p);
            }
        }
        if (targets.isEmpty()) {
            GazoFx.showWarn("画像を削除", "削除できる画像がありません。");
            return;
        }
        int n = targets.size();
        int maxLines = 24;
        StringBuilder preview = new StringBuilder();
        for (int i = 0; i < n && i < maxLines; i++) {
            preview.append("・ ").append(targets.get(i).getFileName().toString()).append('\n');
        }
        if (n > maxLines) {
            preview.append("… 他 ").append(n - maxLines).append(" 件\n");
        }
        Alert confirm =
                new Alert(
                        Alert.AlertType.CONFIRMATION,
                        "Vault から次の画像ファイルを完全に削除します。元に戻せません。\n\n" + preview,
                        ButtonType.OK,
                        ButtonType.CANCEL);
        confirm.setTitle("画像を削除");
        confirm.setHeaderText(n + " 件の画像を削除しますか？");
        if (owner != null) {
            confirm.initOwner(owner);
        }
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) {
            return;
        }
        List<Path> deleted = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (Path p : targets) {
            try {
                vault.deleteImage(p);
                deleted.add(p);
            } catch (IOException ex) {
                failed.add(p.getFileName() + ": " + ex.getMessage());
            }
        }
        for (Path p : deleted) {
            listCheckedSelection.remove(p);
            canvasSelection.remove(p);
        }
        afterDeleted.run();
        if (!failed.isEmpty()) {
            GazoFx.showError("削除エラー", String.join("\n", failed));
        }
    }
}
