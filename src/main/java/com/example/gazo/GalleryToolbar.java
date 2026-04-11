package com.example.gazo;

import com.example.gazo.vault.GazoVaultService;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TextField;
import javafx.scene.Node;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.util.StringConverter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 画像タブツールバーのタグ絞り込み・表示オプション・ファイル名検索・一覧サイズと、
 * {@link VaultPathStore} へのギャラリー設定の保存。
 */
public final class GalleryToolbar {
    private static final List<String> LIST_VIEW_SIZE_OPTIONS = List.of("小", "中", "大");

    private final Runnable refreshGallery;
    private final Runnable refreshVideoList;
    private final Supplier<GazoVaultService> vault;

    private final LinkedHashSet<String> activeTagFilters = new LinkedHashSet<>();
    private final Map<String, Integer> tagFilterCounts = new HashMap<>();
    private final Map<String, BooleanProperty> tagFilterSelectionMap = new LinkedHashMap<>();
    private boolean updatingTagFilterSelection;
    private MenuButton tagFilterMenuButton;
    private ListView<String> tagFilterListView;
    private MenuButton displayOptionsMenuButton;
    private ListView<String> displayOptionsListView;
    private final Map<String, BooleanProperty> displayOptionSelectionMap = new LinkedHashMap<>();
    private boolean updatingDisplayOptionSelection;
    private TextField imageSearchField;
    private ComboBox<String> listSizeCombo;

    private boolean showFileName = true;
    private boolean showDate = true;
    private boolean showTags = true;
    private String imageNameQuery = "";
    private String listViewSize = "中";

    private String initialSearchRaw = "";

    public GalleryToolbar(
            VaultPathStore.GallerySettings initial,
            Supplier<GazoVaultService> vault,
            Runnable refreshGallery,
            Runnable refreshVideoList) {
        this.vault = vault;
        this.refreshGallery = refreshGallery;
        this.refreshVideoList = refreshVideoList;
        applyInitialSettings(initial);
    }

    private void applyInitialSettings(VaultPathStore.GallerySettings gallerySettings) {
        activeTagFilters.clear();
        activeTagFilters.addAll(gallerySettings.tagFilters());
        showFileName = gallerySettings.showFileName();
        showDate = gallerySettings.showDate();
        showTags = gallerySettings.showTags();
        listViewSize = gallerySettings.listViewSize();
        if (!LIST_VIEW_SIZE_OPTIONS.contains(listViewSize)) {
            listViewSize = "中";
        }
        String loadedSearch = gallerySettings.imageNameQuery() == null ? "" : gallerySettings.imageNameQuery();
        initialSearchRaw = loadedSearch;
        imageNameQuery = loadedSearch.trim().toLowerCase();
    }

    /**
     * 「タグ:」ラベルから一覧サイズコンボまでのノード（ツールバー左側の選択・キャンバスボタンの右に並べる）。
     */
    public List<Node> buildToolbarNodes() {
        tagFilterListView = new ListView<>();
        tagFilterListView.setPrefWidth(260);
        tagFilterListView.setPrefHeight(220);
        tagFilterListView.setCellFactory(
                CheckBoxListCell.forListView(
                        this::tagFilterBooleanProperty,
                        new StringConverter<>() {
                            @Override
                            public String toString(String object) {
                                if (object == null) {
                                    return "";
                                }
                                int n = tagFilterCounts.getOrDefault(object, 0);
                                if (TagFilter.UNTAGGED_SENTINEL.equals(object)) {
                                    return "（タグなし） (" + n + ")";
                                }
                                return object + " (" + n + ")";
                            }

                            @Override
                            public String fromString(String string) {
                                return null;
                            }
                        }));
        CustomMenuItem tagFilterMenuItem = new CustomMenuItem(tagFilterListView, false);
        tagFilterMenuButton = new MenuButton("タグ: すべて");
        tagFilterMenuButton.getItems().setAll(tagFilterMenuItem);
        tagFilterMenuButton.setPrefWidth(180);
        Button clearTagFiltersBtn = new Button("×");
        clearTagFiltersBtn.setTooltip(new Tooltip("タグの絞り込みを解除"));
        clearTagFiltersBtn.setFocusTraversable(false);
        clearTagFiltersBtn.setStyle("-fx-font-weight: bold; -fx-padding: 2 8;");
        clearTagFiltersBtn.setOnAction(
                e -> {
                    activeTagFilters.clear();
                    updatingTagFilterSelection = true;
                    try {
                        for (BooleanProperty p : tagFilterSelectionMap.values()) {
                            p.set(false);
                        }
                    } finally {
                        updatingTagFilterSelection = false;
                    }
                    refreshGallery.run();
                    refreshVideoList.run();
                    updateTagFilterButtonText();
                    persistGallerySettings();
                });
        displayOptionsListView = new ListView<>();
        displayOptionsListView.setPrefWidth(170);
        displayOptionsListView.setPrefHeight(124);
        displayOptionsListView.setCellFactory(CheckBoxListCell.forListView(this::displayOptionProperty));
        displayOptionsListView.getItems().setAll("ファイル名", "日付", "タグ");
        CustomMenuItem displayOptionsMenuItem = new CustomMenuItem(displayOptionsListView, false);
        displayOptionsMenuButton = new MenuButton("表示: 3/3");
        displayOptionsMenuButton.getItems().setAll(displayOptionsMenuItem);
        updatingDisplayOptionSelection = true;
        try {
            displayOptionProperty("ファイル名").set(showFileName);
            displayOptionProperty("日付").set(showDate);
            displayOptionProperty("タグ").set(showTags);
        } finally {
            updatingDisplayOptionSelection = false;
        }
        updateDisplayOptionsButtonText();
        listSizeCombo = new ComboBox<>();
        listSizeCombo.getItems().setAll(LIST_VIEW_SIZE_OPTIONS);
        listSizeCombo.setValue(listViewSize);
        listSizeCombo.setOnAction(
                e -> {
                    String selected = listSizeCombo.getValue();
                    listViewSize = selected == null ? "中" : selected;
                    refreshGallery.run();
                    persistGallerySettings();
                });
        imageSearchField = new TextField();
        imageSearchField.setPromptText("ファイル名検索");
        imageSearchField.setPrefWidth(180);
        imageSearchField.setText(initialSearchRaw);
        imageSearchField
                .textProperty()
                .addListener(
                        (obs, oldV, newV) -> {
                            imageNameQuery = newV == null ? "" : newV.trim().toLowerCase();
                            refreshGallery.run();
                            persistGallerySettings();
                        });
        Button clearImageSearchButton = new Button("×");
        clearImageSearchButton.setTooltip(new Tooltip("ファイル名検索をクリア"));
        clearImageSearchButton.setFocusTraversable(false);
        clearImageSearchButton.setStyle("-fx-font-weight: bold; -fx-padding: 2 8;");
        clearImageSearchButton.setOnAction(e -> imageSearchField.clear());
        return List.of(
                new Label("タグ:"),
                tagFilterMenuButton,
                clearTagFiltersBtn,
                displayOptionsMenuButton,
                new Label("検索:"),
                imageSearchField,
                clearImageSearchButton,
                new Label("一覧サイズ:"),
                listSizeCombo);
    }

    public void refreshTagFilterOptions() {
        if (tagFilterListView == null || vault.get() == null) {
            return;
        }
        try {
            Map<String, Set<String>> tagMap = vault.get().tagsByFileName();
            rebuildTagFilterCounts(tagMap);
            List<String> allTags = new ArrayList<>(vault.get().listAllTags());
            Collections.sort(allTags);
            LinkedHashSet<String> allowed = new LinkedHashSet<>(allTags);
            allowed.add(TagFilter.UNTAGGED_SENTINEL);
            activeTagFilters.retainAll(allowed);
            tagFilterSelectionMap.keySet().retainAll(allowed);
            List<String> items = new ArrayList<>(allTags.size() + 1);
            items.add(TagFilter.UNTAGGED_SENTINEL);
            items.addAll(allTags);
            updatingTagFilterSelection = true;
            try {
                tagFilterListView.getItems().setAll(items);
                for (String tag : items) {
                    BooleanProperty prop = tagFilterBooleanProperty(tag);
                    prop.set(activeTagFilters.contains(tag));
                }
            } finally {
                updatingTagFilterSelection = false;
            }
            tagFilterListView.refresh();
            updateTagFilterButtonText();
            persistGallerySettings();
        } catch (IOException e) {
            GazoFx.showError("タグ読み込みエラー", e.getMessage());
        }
    }

    private void rebuildTagFilterCounts(Map<String, Set<String>> tagMap) throws IOException {
        tagFilterCounts.clear();
        int untagged = 0;
        Map<String, Integer> perTag = new HashMap<>();
        GazoVaultService v = vault.get();
        for (Path p : v.listImages()) {
            Set<String> tags = tagMap.getOrDefault(p.getFileName().toString(), Set.of());
            if (tags.isEmpty()) {
                untagged++;
            } else {
                for (String t : tags) {
                    perTag.merge(t, 1, Integer::sum);
                }
            }
        }
        for (Path p : v.listVideos()) {
            Set<String> tags = tagMap.getOrDefault(p.getFileName().toString(), Set.of());
            if (tags.isEmpty()) {
                untagged++;
            } else {
                for (String t : tags) {
                    perTag.merge(t, 1, Integer::sum);
                }
            }
        }
        tagFilterCounts.put(TagFilter.UNTAGGED_SENTINEL, untagged);
        tagFilterCounts.putAll(perTag);
    }

    private BooleanProperty tagFilterBooleanProperty(String tag) {
        return tagFilterSelectionMap.computeIfAbsent(
                tag,
                k -> {
                    SimpleBooleanProperty p = new SimpleBooleanProperty(false);
                    p.addListener(
                            (obs, oldV, newV) -> {
                                if (!updatingTagFilterSelection) {
                                    onTagFilterCheckboxChanged();
                                }
                            });
                    return p;
                });
    }

    private void onTagFilterCheckboxChanged() {
        activeTagFilters.clear();
        for (Map.Entry<String, BooleanProperty> entry : tagFilterSelectionMap.entrySet()) {
            if (entry.getValue().get()) {
                activeTagFilters.add(entry.getKey());
            }
        }
        refreshGallery.run();
        refreshVideoList.run();
        updateTagFilterButtonText();
        persistGallerySettings();
    }

    private void updateTagFilterButtonText() {
        if (tagFilterMenuButton == null) {
            return;
        }
        if (activeTagFilters.isEmpty()) {
            tagFilterMenuButton.setText("タグ: すべて");
            return;
        }
        tagFilterMenuButton.setText("タグ: " + activeTagFilters.size() + "件選択");
    }

    private BooleanProperty displayOptionProperty(String label) {
        return displayOptionSelectionMap.computeIfAbsent(
                label,
                k -> {
                    SimpleBooleanProperty p = new SimpleBooleanProperty(false);
                    p.addListener(
                            (obs, oldV, newV) -> {
                                if (!updatingDisplayOptionSelection) {
                                    onDisplayOptionsChanged();
                                }
                            });
                    return p;
                });
    }

    private void onDisplayOptionsChanged() {
        BooleanProperty fileNameProp = displayOptionSelectionMap.get("ファイル名");
        BooleanProperty dateProp = displayOptionSelectionMap.get("日付");
        BooleanProperty tagProp = displayOptionSelectionMap.get("タグ");
        showFileName = fileNameProp != null && fileNameProp.get();
        showDate = dateProp != null && dateProp.get();
        showTags = tagProp != null && tagProp.get();
        updateDisplayOptionsButtonText();
        refreshGallery.run();
        persistGallerySettings();
    }

    private void persistGallerySettings() {
        String searchRaw = imageSearchField == null ? "" : imageSearchField.getText();
        VaultPathStore.saveGallerySettings(
                new VaultPathStore.GallerySettings(
                        showFileName,
                        showDate,
                        showTags,
                        listViewSize,
                        searchRaw,
                        new ArrayList<>(activeTagFilters)));
    }

    private void updateDisplayOptionsButtonText() {
        if (displayOptionsMenuButton == null) {
            return;
        }
        int selected = 0;
        if (showFileName) {
            selected++;
        }
        if (showDate) {
            selected++;
        }
        if (showTags) {
            selected++;
        }
        displayOptionsMenuButton.setText("表示: " + selected + "/3");
    }

    public boolean showFileName() {
        return showFileName;
    }

    public boolean showDate() {
        return showDate;
    }

    public boolean showTags() {
        return showTags;
    }

    public String listViewSize() {
        return listViewSize;
    }

    public String imageNameQuery() {
        return imageNameQuery;
    }

    /** メインのタグ絞り込み（変更可。キャンバス追加ダイアログ用のコピーは {@link #activeTagFiltersCopy()}）。 */
    public LinkedHashSet<String> activeTagFilters() {
        return activeTagFilters;
    }

    public LinkedHashSet<String> activeTagFiltersCopy() {
        return new LinkedHashSet<>(activeTagFilters);
    }

    public void setShowFileNameOption(boolean enabled) {
        showFileName = enabled;
        BooleanProperty fileNameProp = displayOptionSelectionMap.get("ファイル名");
        if (fileNameProp != null) {
            updatingDisplayOptionSelection = true;
            try {
                fileNameProp.set(enabled);
            } finally {
                updatingDisplayOptionSelection = false;
            }
        }
        updateDisplayOptionsButtonText();
        refreshGallery.run();
        persistGallerySettings();
    }
}
