package dev.tripoder.hooklab;

final class UiInsets {
    static void apply(android.view.View root) {
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                if (view.getWindowInsetsController() != null) {
                    int light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    view.getWindowInsetsController().setSystemBarsAppearance(ProductUi.night ? 0 : light, light);
                }
                if (insets.isVisible(android.view.WindowInsets.Type.ime())) view.post(() -> {
                    android.view.View focused = view.findFocus();
                    if (focused != null) focused.requestRectangleOnScreen(new android.graphics.Rect(0, 0, focused.getWidth(), focused.getHeight()), false);
                });
            } else view.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
    }
}
