/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.ui.account;

import com.jfoenix.controls.JFXButton;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.setting.AuthlibInjectorServerList;
import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.setting.UserSettings;
import org.jackhuang.hmcl.ui.construct.AdvancedListItem;
import org.jackhuang.hmcl.ui.construct.HintPane;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jackhuang.hmcl.ui.construct.TabHeader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.junit.jupiter.api.Assertions.*;

/// Exercises OAuth availability in real account controls without starting authentication or writing settings.
@NotNullByDefault
@EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class MicrosoftOAuthAvailabilityTest {
    /// The runtime override used to simulate configured and unconfigured builds.
    private static final String CLIENT_ID_PROPERTY = "hmcl.microsoft.auth.id";

    /// Verifies the disabled entry retains its explanation and other account entry points remain available.
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "cmcl-test-client-id"})
    void accountListReflectsClientId(String clientId) {
        withEnvironment(clientId, () -> {
            AccountListPage page = new AccountListPage();
            page.setSkin(page.createDefaultSkin());
            ScrollPane methods = (ScrollPane) page.getLeft().getChildren().get(0);
            Node content = Objects.requireNonNull(methods.getContent());
            AdvancedListItem microsoft = findItem(content, "account.methods.microsoft");
            assertEquals(clientId.isBlank(), microsoft.isDisabled());
            assertFalse(findItem(content, "account.methods.offline").isDisabled());
            assertFalse(findItem(page.getLeft(), "account.injector.add").isDisabled());
            if (clientId.isBlank()) {
                Parent wrapper = Objects.requireNonNull(microsoft.getParent());
                assertFalse(wrapper.isDisabled());
                assertTrue(wrapper.getProperties().values().stream().anyMatch(value -> value instanceof Tooltip tooltip
                        && tooltip.getText().equals(i18n("account.methods.microsoft.unavailable"))));
            }
        });
    }

    /// Verifies unavailable and unknown saved defaults fall back without rewriting the saved preference.
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"microsoft", "unknown"})
    void unavailableDefaultFallsBackToOffline(@Nullable String preferred) {
        withEnvironment("", () -> {
            SettingsManager.settings().preferredLoginTypeProperty().set(preferred);
            CreateAccountPane pane = new CreateAccountPane();
            TabHeader tabs = findTabs(pane);
            assertEquals("offline", Objects.requireNonNull(tabs.getSelectionModel().getSelectedItem()).getId());
            assertFalse(tabs.getTabs().stream().anyMatch(tab -> "microsoft".equals(tab.getId())));
            assertTrue(tabs.getTabs().stream().anyMatch(tab -> "authlibInjector".equals(tab.getId())));
            assertEquals(preferred, SettingsManager.settings().preferredLoginTypeProperty().get());
            tabs.getSelectionModel().select(tabs.getTabs().stream()
                    .filter(tab -> "authlibInjector".equals(tab.getId())).findFirst().orElseThrow());
            assertEquals("authlibInjector", SettingsManager.settings().preferredLoginTypeProperty().get());
        });
    }

    /// Verifies configuring OAuth restores the Microsoft option without starting a login in the test.
    @Test
    void configuredBuildRetainsMicrosoftOption() {
        withEnvironment("cmcl-test-client-id", () -> {
            SettingsManager.settings().preferredLoginTypeProperty().set("offline");
            assertTrue(findTabs(new CreateAccountPane()).getTabs().stream()
                    .anyMatch(tab -> "microsoft".equals(tab.getId())));
            assertSame(Accounts.FACTORY_MICROSOFT, Accounts.getAccountFactory("microsoft"));
        });
    }

    /// Verifies direct sign-in dialogs cannot launch browser or device authentication without OAuth.
    @ParameterizedTest
    @ValueSource(strings = {"", " \t"})
    void unconfiguredDialogBlocksAllLoginMethods(String clientId) {
        withEnvironment(clientId, () -> assertUnavailable(new MicrosoftAccountLoginPane()));
    }

    /// Verifies explicit callers still receive an explanatory disabled Microsoft dialog.
    @Test
    void explicitMicrosoftFactoryCannotBypassAvailability() {
        withEnvironment("", () -> {
            CreateAccountPane pane = new CreateAccountPane(Accounts.FACTORY_MICROSOFT);
            MicrosoftAccountLoginPane login = descendants(pane).filter(MicrosoftAccountLoginPane.class::isInstance)
                    .map(MicrosoftAccountLoginPane.class::cast).findFirst().orElseThrow();
            assertUnavailable(login);
        });
    }

    /// Verifies a forced offline restriction is not bypassed by the default-account fallback.
    @Test
    void explicitOfflineRestrictionRemainsEffective() {
        withEnvironment("", () -> {
            AccountListPage.RESTRICTED.set(true);
            CreateAccountPane pane = new CreateAccountPane();
            assertFalse(descendants(pane).anyMatch(TabHeader.class::isInstance));
            MicrosoftAccountLoginPane login = descendants(pane).filter(MicrosoftAccountLoginPane.class::isInstance)
                    .map(MicrosoftAccountLoginPane.class::cast).findFirst().orElseThrow();
            assertUnavailable(login);
        });
    }

    /// Verifies the login button is disabled, the explanation is attached, and device login is absent.
    private static void assertUnavailable(MicrosoftAccountLoginPane pane) {
        SpinnerPane spinner = (SpinnerPane) pane.getActions().get(0);
        JFXButton login = (JFXButton) Objects.requireNonNull(spinner.getContent());
        assertTrue(login.isDisabled());
        assertTrue(descendants(pane).filter(HintPane.class::isInstance).map(HintPane.class::cast)
                .anyMatch(hint -> i18n("account.methods.microsoft.unavailable").equals(hint.getText())));
        assertFalse(descendants(pane).anyMatch(node -> node instanceof javafx.scene.control.Labeled label
                && i18n("account.methods.microsoft.methods.device").equals(label.getText())));
    }

    /// Finds an account action by its localized title.
    private static AdvancedListItem findItem(Node root, String titleKey) {
        return descendants(root).filter(AdvancedListItem.class::isInstance).map(AdvancedListItem.class::cast)
                .filter(item -> i18n(titleKey).equals(item.getTitle())).findFirst().orElseThrow();
    }

    /// Finds the authentication method switcher in a newly constructed account dialog.
    private static TabHeader findTabs(CreateAccountPane pane) {
        return descendants(pane).filter(TabHeader.class::isInstance).map(TabHeader.class::cast)
                .findFirst().orElseThrow();
    }

    /// Traverses the attached scene graph without showing a native window.
    private static Stream<Node> descendants(Node root) {
        return Stream.concat(Stream.of(root), root instanceof Parent parent
                ? parent.getChildrenUnmodifiable().stream().flatMap(MicrosoftOAuthAvailabilityTest::descendants)
                : Stream.empty());
    }

    /// Installs in-memory settings and an OAuth override on the FX thread, restoring all state afterward.
    private static void withEnvironment(String clientId, Runnable assertions) {
        FutureTask<Boolean> task = new FutureTask<>(() -> {
            Field settings = SettingsManager.class.getDeclaredField("launcherSettings");
            Field users = SettingsManager.class.getDeclaredField("userSettingsInstance");
            Field servers = SettingsManager.class.getDeclaredField("authlibInjectorServers");
            settings.setAccessible(true);
            users.setAccessible(true);
            servers.setAccessible(true);
            @Nullable Object previousSettings = settings.get(null);
            @Nullable Object previousUsers = users.get(null);
            @Nullable Object previousServers = servers.get(null);
            @Nullable String previousClientId = System.getProperty(CLIENT_ID_PROPERTY);
            settings.set(null, new LauncherSettings());
            users.set(null, new UserSettings());
            servers.set(null, new AuthlibInjectorServerList());
            boolean previousRestricted = AccountListPage.RESTRICTED.get();
            try {
                AccountListPage.RESTRICTED.set(false);
                System.setProperty(CLIENT_ID_PROPERTY, clientId);
                assertions.run();
            } finally {
                AccountListPage.RESTRICTED.set(previousRestricted);
                settings.set(null, previousSettings);
                users.set(null, previousUsers);
                servers.set(null, previousServers);
                if (previousClientId == null) {
                    System.clearProperty(CLIENT_ID_PROPERTY);
                } else {
                    System.setProperty(CLIENT_ID_PROPERTY, previousClientId);
                }
            }
            return true;
        });
        Platform.runLater(task);
        try {
            task.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new AssertionError(e);
        }
    }
}
