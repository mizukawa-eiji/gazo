package com.example.gazo;

import java.nio.file.Path;

/**
 * メイン画像一覧のスナップカードから呼ばれる操作（タグ・重複チェック・オリジナル表示など）。
 */
public interface ImageGalleryCardActions {
    void openOriginalViewer(Path path);

    void editTags(Path path);

    void bulkAddTagsToSelection();

    void bulkRemoveTagsFromSelection();

    void duplicateCheckThisImage(Path path);

    void duplicateCheckSelection();

    /** チェックボックス変更後、ツールバー／メニューの有効化を更新する。 */
    void onGalleryCardSelectionChanged();
}
