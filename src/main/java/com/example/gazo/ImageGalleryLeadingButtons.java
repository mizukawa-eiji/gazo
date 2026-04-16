package com.example.gazo;

import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;

/**
 * 画像タブツールバー左側（全選択〜ランダムキャンバス・選択削除）。
 */
public final class ImageGalleryLeadingButtons {
    private ImageGalleryLeadingButtons() {}

    public record Result(
            Button selectAll,
            Button clearSelection,
            Button deleteChecked,
            Button canvasHub,
            Button randomHub) {}

    public static Result create(
            Runnable onSelectAllVisible,
            Runnable onClearListSelection,
            Runnable onDeleteCheckedFromVault,
            Runnable onCanvasHub,
            Runnable onRandomCanvasHub,
            Runnable refreshSelectionDependentMenuState) {
        Button selectAll = new Button("全選択");
        selectAll.setOnAction(e -> onSelectAllVisible.run());
        Button clearSelection = new Button("クリア");
        clearSelection.setOnAction(e -> onClearListSelection.run());
        Button galleryDeleteCheckedButton = new Button("選択を削除");
        galleryDeleteCheckedButton.setTooltip(new Tooltip("チェックした画像をアルバムから削除（元に戻せません）"));
        galleryDeleteCheckedButton.setOnAction(e -> onDeleteCheckedFromVault.run());
        Button canvasHub = new Button("選択画像でキャンバス作成");
        canvasHub.setOnAction(e -> onCanvasHub.run());
        Button randomCanvasHub = new Button("ランダムにキャンバスを作成");
        randomCanvasHub.setOnAction(e -> onRandomCanvasHub.run());
        refreshSelectionDependentMenuState.run();
        return new Result(selectAll, clearSelection, galleryDeleteCheckedButton, canvasHub, randomCanvasHub);
    }
}
