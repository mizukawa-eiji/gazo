package com.example.gazo;

import javafx.scene.image.Image;

import java.nio.file.Path;

@FunctionalInterface
public interface CanvasThumbnailLoader {
    Image load(Path path, int width, int height);
}
