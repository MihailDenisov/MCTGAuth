package dev.example.mcauth.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class MCTGAuthSettingsScreen extends Screen {
    private final Screen parent;
    private final MCTGAuthClientConfig config;
    private final AuthNetworkClient controller;
    private final boolean authenticationRequired;
    private final boolean rejectedStoredPassword;
    private EditBox password;
    private Button showPassword;
    private Button autoAuthenticate;
    private boolean passwordVisible;
    private boolean automatic;

    public MCTGAuthSettingsScreen(Screen parent, MCTGAuthClientConfig config) {
        this(parent, config, null, false, false);
    }

    public MCTGAuthSettingsScreen(Screen parent, MCTGAuthClientConfig config,
                                  AuthNetworkClient controller, boolean authenticationRequired,
                                  boolean rejectedStoredPassword) {
        super(Component.translatable("mctgauth.settings.title"));
        this.parent = parent;
        this.config = config;
        this.controller = controller;
        this.authenticationRequired = authenticationRequired;
        this.rejectedStoredPassword = rejectedStoredPassword;
    }

    @Override
    protected void init() {
        MCTGAuthClientConfig.Snapshot saved = config.snapshot();
        automatic = saved.autoAuthenticate();
        int center = width / 2;
        int top = Math.max(45, height / 2 - 85);
        password = new EditBox(font, center - 100, top + 28, 200, 20,
                Component.translatable("mctgauth.settings.password"));
        password.setMaxLength(256);
        password.setValue(saved.password());
        updatePasswordFormatter();
        addRenderableWidget(password);
        showPassword = addRenderableWidget(Button.builder(showPasswordLabel(), button -> {
            passwordVisible = !passwordVisible;
            updatePasswordFormatter();
            button.setMessage(showPasswordLabel());
        }).bounds(center - 100, top + 54, 200, 20).build());
        autoAuthenticate = addRenderableWidget(Button.builder(autoLabel(), button -> {
            automatic = !automatic;
            button.setMessage(autoLabel());
        }).bounds(center - 100, top + 80, 200, 20).build());

        Component saveLabel = Component.translatable(authenticationRequired
                ? "mctgauth.action.save_authenticate" : "mctgauth.action.save");
        addRenderableWidget(Button.builder(saveLabel, button -> save()).bounds(center - 100, top + 112, 96, 20).build());
        addRenderableWidget(Button.builder(Component.translatable(authenticationRequired
                        ? "mctgauth.action.disconnect" : "mctgauth.action.cancel"), button -> cancel())
                .bounds(center + 4, top + 112, 96, 20).build());
        setInitialFocus(password);
    }

    private void save() {
        String value = password.getValue();
        config.save(value, automatic);
        if (authenticationRequired && controller != null && !value.isEmpty()) {
            controller.submit(value.toCharArray());
            return;
        }
        if (minecraft != null) minecraft.setScreen(parent);
    }

    private void cancel() {
        if (minecraft == null) return;
        if (authenticationRequired) minecraft.disconnect();
        else minecraft.setScreen(parent);
    }

    private void updatePasswordFormatter() {
        password.setFormatter((value, offset) -> Component.literal(passwordVisible
                ? value : "*".repeat(value.length())).getVisualOrderText());
    }

    private Component showPasswordLabel() {
        return Component.translatable(passwordVisible ? "mctgauth.settings.hide_password"
                : "mctgauth.settings.show_password");
    }

    private Component autoLabel() {
        return Component.translatable("mctgauth.settings.auto_authenticate",
                Component.translatable(automatic ? "options.on" : "options.off"));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == 257 || keyCode == 335) && password != null) {
            save();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !authenticationRequired;
    }

    @Override
    public void onClose() {
        cancel();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int center = width / 2;
        int top = Math.max(45, height / 2 - 85);
        graphics.drawCenteredString(font, title, center, top, 0xFFFFFF);
        graphics.drawString(font, Component.translatable("mctgauth.settings.password"), center - 100, top + 16, 0xDDDDDD);
        Component message = Component.translatable(rejectedStoredPassword
                ? "mctgauth.auth.stored_rejected"
                : authenticationRequired ? "mctgauth.auth.password_required" : "mctgauth.settings.storage_warning");
        graphics.drawWordWrap(font, message, center - 140, top + 140, 280,
                rejectedStoredPassword ? 0xFF7777 : 0xAAAAAA);
    }
}
