package com.example.gazo;

import javafx.application.Platform;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

/**
 * メインウィンドウの「ファイル」「操作」メニュー。
 */
public final class GazoMenuBarFactory {
    private GazoMenuBarFactory() {}

    public record Result(
            MenuBar menuBar,
            MenuItem menuGalleryTagBulkAdd,
            MenuItem menuGalleryTagBulkRemove,
            MenuItem menuGalleryDeleteCheckedImages) {}

    public static Result create(
            Runnable onAddImages,
            Runnable onAddVideos,
            Runnable onRebuildThumbnails,
            Runnable onDuplicateCheck,
            Runnable onBulkAddTags,
            Runnable onBulkRemoveTags,
            Runnable onDeleteCheckedImages,
            Runnable onChangeVault,
            Runnable onConflictThresholdSettings,
            Runnable onMenuActionsShowing) {
        MenuItem addMenu = new MenuItem("画像を追加…");
        addMenu.setOnAction(e -> onAddImages.run());
        MenuItem addVideosMenu = new MenuItem("動画を追加…");
        addVideosMenu.setOnAction(e -> onAddVideos.run());
        MenuItem rebuildThumbsMenu = new MenuItem("サムネイル再作成…");
        rebuildThumbsMenu.setOnAction(e -> onRebuildThumbnails.run());
        MenuItem duplicateMenu = new MenuItem("重複/類似チェック...");
        duplicateMenu.setOnAction(e -> onDuplicateCheck.run());
        MenuItem bulkAddMenu = new MenuItem("タグ一括追加...");
        bulkAddMenu.setOnAction(e -> onBulkAddTags.run());
        MenuItem bulkRemoveMenu = new MenuItem("タグ一括削除...");
        bulkRemoveMenu.setOnAction(e -> onBulkRemoveTags.run());
        MenuItem deleteCheckedImagesMenu = new MenuItem("チェックした画像を削除…");
        deleteCheckedImagesMenu.setOnAction(e -> onDeleteCheckedImages.run());
        MenuItem changeVaultMenu = new MenuItem("アルバム変更…");
        changeVaultMenu.setOnAction(e -> onChangeVault.run());
        MenuItem conflictThresholdMenu = new MenuItem("競合比較しきい値設定…");
        conflictThresholdMenu.setOnAction(e -> onConflictThresholdSettings.run());
        MenuItem exitMenu = new MenuItem("終了");
        exitMenu.setOnAction(e -> Platform.exit());

        Menu fileMenu = new Menu("ファイル");
        fileMenu.getItems()
                .addAll(
                        addMenu,
                        addVideosMenu,
                        rebuildThumbsMenu,
                        new SeparatorMenuItem(),
                        changeVaultMenu,
                        conflictThresholdMenu,
                        new SeparatorMenuItem(),
                        exitMenu);

        Menu actionsMenu = new Menu("操作");
        actionsMenu.getItems()
                .addAll(
                        duplicateMenu,
                        new SeparatorMenuItem(),
                        bulkAddMenu,
                        bulkRemoveMenu,
                        deleteCheckedImagesMenu,
                        new SeparatorMenuItem());
        actionsMenu.setOnShowing(e -> onMenuActionsShowing.run());
        return new Result(new MenuBar(fileMenu, actionsMenu), bulkAddMenu, bulkRemoveMenu, deleteCheckedImagesMenu);
    }
}
