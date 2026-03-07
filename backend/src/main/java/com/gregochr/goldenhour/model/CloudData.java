package com.gregochr.goldenhour.model;

/**
 * Observer-point cloud cover at three altitude layers.
 *
 * @param lowCloudPercent  low cloud cover percentage (0-100, 0-3 km)
 * @param midCloudPercent  mid cloud cover percentage (0-100, 3-8 km)
 * @param highCloudPercent high cloud cover percentage (0-100, 8+ km)
 */
public record CloudData(
        int lowCloudPercent,
        int midCloudPercent,
        int highCloudPercent) {
}
