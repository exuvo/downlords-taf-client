package com.faforever.client.main;

import ch.micheljung.fxwindow.FxStage;
import com.faforever.client.FafClientApplication;
import com.faforever.client.chat.event.UnreadPartyMessageEvent;
import com.faforever.client.chat.event.UnreadPrivateMessageEvent;
import com.faforever.client.config.ClientProperties;
import com.faforever.client.fx.AbstractViewController;
import com.faforever.client.fx.Controller;
import com.faforever.client.fx.JavaFxUtil;
import com.faforever.client.fx.PlatformService;
import com.faforever.client.game.GamePathHandler;
import com.faforever.client.game.KnownFeaturedMod;
import com.faforever.client.i18n.I18n;
import com.faforever.client.login.LoginController;
import com.faforever.client.main.event.NavigateEvent;
import com.faforever.client.main.event.NavigationItem;
import com.faforever.client.news.UnreadNewsEvent;
import com.faforever.client.notification.Action;
import com.faforever.client.notification.ImmediateNotification;
import com.faforever.client.notification.ImmediateNotificationController;
import com.faforever.client.notification.NotificationService;
import com.faforever.client.notification.PersistentNotification;
import com.faforever.client.notification.PersistentNotificationsController;
import com.faforever.client.notification.ServerNotificationController;
import com.faforever.client.notification.Severity;
import com.faforever.client.notification.TransientNotificationsController;
import com.faforever.client.preferences.PreferencesService;
import com.faforever.client.preferences.WindowPrefs;
import com.faforever.client.preferences.ui.SettingsController;
import com.faforever.client.remote.FafService;
import com.faforever.client.task.ResourceLocks;
import com.faforever.client.theme.UiService;
import com.faforever.client.ui.StageHolder;
import com.faforever.client.ui.alert.Alert;
import com.faforever.client.ui.alert.animation.AlertAnimation;
import com.faforever.client.ui.tray.event.UpdateApplicationBadgeEvent;
import com.faforever.client.user.event.LoggedInEvent;
import com.faforever.client.user.event.LoggedOutEvent;
import com.faforever.client.user.event.LoginSuccessEvent;
import com.faforever.client.util.ZipUtil;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.event.ActionEvent;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.input.InputEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundImage;
import javafx.scene.layout.BackgroundPosition;
import javafx.scene.layout.BackgroundRepeat;
import javafx.scene.layout.BackgroundSize;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.stage.Popup;
import javafx.stage.PopupWindow.AnchorLocation;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Scope;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.faforever.client.game.GameService.CUSTOM_GAME_CHANNEL_REGEX;
import static com.github.nocatch.NoCatch.noCatch;
import static javafx.scene.layout.Background.EMPTY;

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@Slf4j
// TODO divide and conquer
public class MainController implements Controller<Node> {
  private static final PseudoClass NOTIFICATION_INFO_PSEUDO_CLASS = PseudoClass.getPseudoClass("info");
  private static final PseudoClass NOTIFICATION_WARN_PSEUDO_CLASS = PseudoClass.getPseudoClass("warn");
  private static final PseudoClass NOTIFICATION_ERROR_PSEUDO_CLASS = PseudoClass.getPseudoClass("error");
  private static final PseudoClass HIGHLIGHTED = PseudoClass.getPseudoClass("highlighted");
  private final Cache<NavigationItem, AbstractViewController<?>> viewCache;
  private final PreferencesService preferencesService;
  private final I18n i18n;
  private final NotificationService notificationService;
  private final UiService uiService;
  private final EventBus eventBus;
  private final GamePathHandler gamePathHandler;
  private final PlatformService platformService;
  private final ApplicationEventPublisher applicationEventPublisher;
  private final String mainWindowTitle;
  private final boolean alwaysReloadTabs;
  private final FafService fafService;

  public Pane mainHeaderPane;
  public Pane contentPane;
  public ToggleButton newsButton;
  public ToggleButton playButton;
  public ToggleButton matchmakerButton;
  public ToggleButton replayButton;
  public ToggleButton tutorialsButton;
  public ToggleButton mapButton;
  public ToggleButton modButton;
  public ToggleButton leaderboardsButton;
  public ToggleButton tournamentsButton;
  public ToggleButton unitsButton;
  public ToggleButton tadaButton;
  public StackPane contentWrapperPane;
  public ToggleGroup mainNavigation;
  public StackPane mainRoot;
  public Pane leftMenuPane;
  public Pane rightMenuPane;
  public Button notificationButton;
  /** Dropdown for when there is not enough room for all navigation buttons to be displayed. */
  public MenuButton navigationDropdown;

  @VisibleForTesting
  protected Popup transientNotificationsPopup;
  @VisibleForTesting
  Popup persistentNotificationsPopup;
  private NavigationItem currentItem;
  private FxStage fxStage;
  private DiscordSelectionMenuController discordSelectionMenuController;


  @Inject
  public MainController(PreferencesService preferencesService, I18n i18n,
                        NotificationService notificationService,
                        UiService uiService, EventBus eventBus,
                        GamePathHandler gamePathHandler, PlatformService platformService,
                        ClientProperties clientProperties,
                        ApplicationEventPublisher applicationEventPublisher,
                        Environment environment,
                        FafService fafService) {
    this.preferencesService = preferencesService;
    this.i18n = i18n;
    this.notificationService = notificationService;
    this.uiService = uiService;
    this.eventBus = eventBus;
    this.gamePathHandler = gamePathHandler;
    this.platformService = platformService;
    this.applicationEventPublisher = applicationEventPublisher;
    this.viewCache = CacheBuilder.newBuilder().build();
    this.mainWindowTitle = clientProperties.getMainWindowTitle();
    this.discordSelectionMenuController = uiService.loadFxml("theme/discord_selection_menu.fxml");
    this.fafService = fafService;
    alwaysReloadTabs = Arrays.asList(environment.getActiveProfiles()).contains(FafClientApplication.PROFILE_RELOAD);
  }

  /**
   * Hides the install4j splash screen. The hide method is invoked via reflection to accommodate starting the client
   * without install4j (e.g. on linux).
   */
  private static void hideSplashScreen() {
    try {
      final Class splashScreenClass = Class.forName("com.install4j.api.launcher.SplashScreen");
      final Method hideMethod = splashScreenClass.getDeclaredMethod("hide");
      hideMethod.invoke(null);
    } catch (ClassNotFoundException e) {
      log.debug("No install4j splash screen found to close.");
    } catch (NoSuchMethodException | IllegalAccessException e) {
      log.error("Couldn't close install4j splash screen.", e);
    } catch (InvocationTargetException e) {
      log.error("Couldn't close install4j splash screen.", e.getCause());
    }
  }

  public void initialize() {
    newsButton.setUserData(NavigationItem.NEWS);
    playButton.setUserData(NavigationItem.PLAY);
    matchmakerButton.setUserData(NavigationItem.MATCHMAKER);
    replayButton.setUserData(NavigationItem.REPLAY);
    mapButton.setUserData(NavigationItem.MAP);
    modButton.setUserData(NavigationItem.MOD);
    leaderboardsButton.setUserData(NavigationItem.LEADERBOARD);
    tournamentsButton.setUserData(NavigationItem.TOURNAMENTS);
    unitsButton.setUserData(NavigationItem.UNITS);
    tutorialsButton.setUserData(NavigationItem.TUTORIALS);
    tadaButton.setUserData(NavigationItem.TADA);
    eventBus.register(this);

    PersistentNotificationsController persistentNotificationsController = uiService.loadFxml("theme/persistent_notifications.fxml");
    persistentNotificationsPopup = new Popup();
    persistentNotificationsPopup.getContent().setAll(persistentNotificationsController.getRoot());
    persistentNotificationsPopup.setAnchorLocation(AnchorLocation.CONTENT_TOP_RIGHT);
    persistentNotificationsPopup.setAutoFix(true);
    persistentNotificationsPopup.setAutoHide(true);

    TransientNotificationsController transientNotificationsController = uiService.loadFxml("theme/transient_notifications.fxml");
    transientNotificationsPopup = new Popup();
    transientNotificationsPopup.setAutoFix(true);
    transientNotificationsPopup.getScene().getRoot().getStyleClass().add("transient-notification");
    transientNotificationsPopup.getContent().setAll(transientNotificationsController.getRoot());

    transientNotificationsController.getRoot().getChildren().addListener(new ToastDisplayer(transientNotificationsController));

    updateNotificationsButton(Collections.emptyList());
    notificationService.addPersistentNotificationListener(change -> JavaFxUtil.runLater(() -> updateNotificationsButton(change.getSet())));
    notificationService.addImmediateNotificationListener(notification -> JavaFxUtil.runLater(() -> displayImmediateNotification(notification)));
    notificationService.addServerNotificationListener(notification -> JavaFxUtil.runLater(() -> displayServerNotification(notification)));
    notificationService.addTransientNotificationListener(notification -> JavaFxUtil.runLater(() -> transientNotificationsController.addNotification(notification)));
    // Always load chat immediately so messages or joined channels don't need to be cached until we display them.
    // Axle1975: which is all good and well if username is known at this point ... ChatController.initialize now adds tabs for pre-existing channels (messages still get lost until player opens chat tho)
    // getView(NavigationItem.PLAY);

    notificationButton.managedProperty().bind(notificationButton.visibleProperty());

    navigationDropdown.getItems().setAll(createMenuItemsFromNavigation());
    navigationDropdown.managedProperty().bind(navigationDropdown.visibleProperty());

    leftMenuPane.layoutBoundsProperty().addListener(observable -> {
      navigationDropdown.setVisible(false);
      leftMenuPane.getChildrenUnmodifiable().forEach(node -> {
        Bounds boundsInParent = node.getBoundsInParent();
        // First time this is called, minY is negative. This is hacky but better than wasting time investigating.
        boolean hasSpace = boundsInParent.getMinY() < 0
            || leftMenuPane.getLayoutBounds().contains(boundsInParent.getCenterX(), boundsInParent.getCenterY());
        if (!hasSpace) {
          navigationDropdown.setVisible(true);
        }
        node.setVisible(hasSpace);
      });
    });

    leftMenuPane.getChildrenUnmodifiable().stream()
        .filter(menuItem -> menuItem.getUserData() instanceof NavigationItem)
        .forEach(menuItem -> menuItem.managedProperty().bind(menuItem.disabledProperty().not()));

    StageHolder.getStage().getScene().addEventFilter(InputEvent.ANY, event -> fafService.resetIdleSince());
  }

  private List<MenuItem> createMenuItemsFromNavigation() {
    return leftMenuPane.getChildrenUnmodifiable().stream()
        .filter(menuItem -> menuItem.getUserData() instanceof NavigationItem)
        .filter(menuItem -> !menuItem.isDisabled() && menuItem.isVisible())
        .map(menuButton -> {
          MenuItem menuItem = new MenuItem(((Labeled) menuButton).getText());
          menuItem.setOnAction(event -> eventBus.post(new NavigateEvent((NavigationItem) menuButton.getUserData())));
          return menuItem;
        })
        .collect(Collectors.toList());
  }

  @Subscribe
  public void onLoginSuccessEvent(LoginSuccessEvent event) {
    JavaFxUtil.runLater(this::enterLoggedInState);
  }

  @Subscribe
  public void onLoggedOutEvent(LoggedOutEvent event) {
    JavaFxUtil.runLater(this::enterLoggedOutState);
  }

  @Subscribe
  public void onUnreadNews(UnreadNewsEvent event) {
    JavaFxUtil.runLater(() -> newsButton.pseudoClassStateChanged(HIGHLIGHTED, event.hasUnreadNews()));
  }

  @Subscribe
  public void onUnreadPartyMessage(UnreadPartyMessageEvent event) {
    if (event.getMessage().getSource() != null) {
      JavaFxUtil.runLater(() -> {
        if (event.getMessage().getSource().matches(CUSTOM_GAME_CHANNEL_REGEX)) {
          playButton.pseudoClassStateChanged(HIGHLIGHTED, !currentItem.equals(NavigationItem.PLAY));
        } else {
          matchmakerButton.pseudoClassStateChanged(HIGHLIGHTED, !currentItem.equals(NavigationItem.MATCHMAKER));
        }
      });
    }
  }

  @Subscribe
  public void onUnreadPrivateMessage(UnreadPrivateMessageEvent event) {
    JavaFxUtil.runLater(() -> playButton.pseudoClassStateChanged(HIGHLIGHTED, !currentItem.equals(NavigationItem.PLAY)));
  }

  private void displayView(AbstractViewController<?> controller, NavigateEvent navigateEvent) {
    Node node = controller.getRoot();
    ObservableList<Node> children = contentPane.getChildren();

    if (alwaysReloadTabs) {
      children.clear();
    }

    if (!children.contains(node)) {
      children.add(node);
      JavaFxUtil.setAnchors(node, 0d);
    }

    if (!alwaysReloadTabs) {
      Optional.ofNullable(currentItem).ifPresent(item -> getView(item).hide());
    }
    controller.display(navigateEvent);
  }

  private Rectangle2D getTransientNotificationAreaBounds() {
    ObservableList<Screen> screens = Screen.getScreens();

    int toastScreenIndex = preferencesService.getPreferences().getNotification().getToastScreen();
    Screen screen;
    if (toastScreenIndex < screens.size()) {
      screen = screens.get(Math.max(0, toastScreenIndex));
    } else {
      screen = Screen.getPrimary();
    }
    return screen.getVisualBounds();
  }

  /**
   * Updates the number displayed in the notifications button and sets its CSS pseudo class based on the highest
   * notification {@code Severity} of all current notifications.
   */
  private void updateNotificationsButton(Collection<? extends PersistentNotification> notifications) {
    JavaFxUtil.assertApplicationThread();

    int size = notifications.size();
    notificationButton.setVisible(size != 0);

    Severity highestSeverity = notifications.stream()
        .map(PersistentNotification::getSeverity)
        .max(Enum::compareTo)
        .orElse(null);

    notificationButton.pseudoClassStateChanged(NOTIFICATION_INFO_PSEUDO_CLASS, highestSeverity == Severity.INFO);
    notificationButton.pseudoClassStateChanged(NOTIFICATION_WARN_PSEUDO_CLASS, highestSeverity == Severity.WARN);
    notificationButton.pseudoClassStateChanged(NOTIFICATION_ERROR_PSEUDO_CLASS, highestSeverity == Severity.ERROR);
  }

  public void display() {
    eventBus.post(UpdateApplicationBadgeEvent.ofNewValue(0));

    Stage stage = StageHolder.getStage();
    setBackgroundImage(preferencesService.getPreferences().getMainWindow().getBackgroundImagePath());

    final WindowPrefs mainWindowPrefs = preferencesService.getPreferences().getMainWindow();
    int width = mainWindowPrefs.getWidth();
    int height = mainWindowPrefs.getHeight();

    stage.setMinWidth(10);
    stage.setMinHeight(10);
    stage.setWidth(width);
    stage.setHeight(height);
    stage.show();

    hideSplashScreen();
    enterLoggedOutState();

    JavaFxUtil.assertApplicationThread();
    stage.setMaximized(mainWindowPrefs.getMaximized());
    if (!stage.isMaximized()) {
      setWindowPosition(stage, mainWindowPrefs);
    }
    registerWindowListeners();
    notificationService.flushPendingImmediateNotifications();
  }

  private void setWindowPosition(Stage stage, WindowPrefs mainWindowPrefs) {
    double x = mainWindowPrefs.getX();
    double y = mainWindowPrefs.getY();
    int width = mainWindowPrefs.getWidth();
    int height = mainWindowPrefs.getHeight();
    ObservableList<Screen> screensForRectangle = Screen.getScreensForRectangle(x, y, width, height);
    if (screensForRectangle.isEmpty()) {
      JavaFxUtil.centerOnScreen(stage);
    } else {
      stage.setX(x);
      stage.setY(y);
    }
  }

  private void enterLoggedOutState() {
    fxStage.getStage().setTitle(i18n.get("login.title"));

    LoginController loginController = uiService.loadFxml("theme/login.fxml");
    fxStage.setContent(loginController.getRoot());
    loginController.display();

    fxStage.getNonCaptionNodes().clear();
  }

  private void registerWindowListeners() {
    Stage stage = fxStage.getStage();
    final WindowPrefs mainWindowPrefs = preferencesService.getPreferences().getMainWindow();
    JavaFxUtil.addListener(stage.heightProperty(), (observable, oldValue, newValue) -> {
      if (!stage.isMaximized()) {
        mainWindowPrefs.setHeight(newValue.intValue());
        preferencesService.storeInBackground();
      }
    });
    JavaFxUtil.addListener(stage.widthProperty(), (observable, oldValue, newValue) -> {
      if (!stage.isMaximized()) {
        mainWindowPrefs.setWidth(newValue.intValue());
        preferencesService.storeInBackground();
      }
    });
    JavaFxUtil.addListener(stage.xProperty(), observable -> {
      if (!stage.isMaximized()) {
        mainWindowPrefs.setX(stage.getX());
        preferencesService.storeInBackground();
      }
    });
    JavaFxUtil.addListener(stage.yProperty(), observable -> {
      if (!stage.isMaximized()) {
        mainWindowPrefs.setY(stage.getY());
        preferencesService.storeInBackground();
      }
    });
    JavaFxUtil.addListener(stage.maximizedProperty(), observable -> {
      mainWindowPrefs.setMaximized(stage.isMaximized());
      preferencesService.storeInBackground();
      if (!stage.isMaximized()) {
        setWindowPosition(stage, mainWindowPrefs);
      }
    });
    JavaFxUtil.addListener(mainWindowPrefs.backgroundImagePathProperty(), observable -> {
      setBackgroundImage(mainWindowPrefs.getBackgroundImagePath());
    });
  }

  private void setBackgroundImage(Path filepath) {
    Image image;
    if (filepath != null && Files.exists(filepath)) {
      image = noCatch(() -> new Image(filepath.toUri().toURL().toExternalForm()));
      mainRoot.setBackground(new Background(new BackgroundImage(
          image,
          BackgroundRepeat.NO_REPEAT,
          BackgroundRepeat.NO_REPEAT,
          BackgroundPosition.CENTER,
          new BackgroundSize(BackgroundSize.AUTO, BackgroundSize.AUTO, false, false, false, true)
      )));
    } else {
      mainRoot.setBackground(EMPTY);
    }
  }

  private void enterLoggedInState() {
    Stage stage = StageHolder.getStage();
    stage.setTitle(mainWindowTitle);

    fxStage.setContent(getRoot());
    fxStage.getNonCaptionNodes().setAll(leftMenuPane, rightMenuPane, navigationDropdown);
    fxStage.setTitleBar(mainHeaderPane);

    applicationEventPublisher.publishEvent(new LoggedInEvent());


    gamePathHandler.detectAndUpdateGamePath(KnownFeaturedMod.DEFAULT.getTechnicalName(), KnownFeaturedMod.DEFAULT.getExecutableFileName());
    openStartTab();
  }

  @VisibleForTesting
  void openStartTab() {
    eventBus.post(new NavigateEvent(NavigationItem.PLAY));
  }

  public void onNotificationsButtonClicked() {
    Bounds screenBounds = notificationButton.localToScreen(notificationButton.getBoundsInLocal());
    persistentNotificationsPopup.show(notificationButton.getScene().getWindow(), screenBounds.getMaxX(), screenBounds.getMaxY());
  }

  public void onSettingsSelected() {
    SettingsController settingsController = uiService.loadFxml("theme/settings/settings.fxml");
    FxStage fxStage = FxStage.create(settingsController.getRoot())
        .initOwner(mainRoot.getScene().getWindow())
        .withSceneFactory(uiService::createScene)
        .allowMinimize(false)
        .apply()
        .setTitleBar(settingsController.settingsHeader);

    Stage stage = fxStage.getStage();
    stage.showingProperty().addListener(new ChangeListener<>() {
      @Override
      public void changed(ObservableValue<? extends Boolean> observable, Boolean oldValue, Boolean newValue) {
        if (!newValue) {
          stage.showingProperty().removeListener(this);
          preferencesService.storeInBackground();
        }
      }
    });

    stage.setTitle(i18n.get("settings.windowTitle"));
    stage.show();
  }

  public void onExitItemSelected() {
    Stage stage = fxStage.getStage();
    stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
  }

  public Pane getRoot() {
    return mainRoot;
  }

  public void onNavigateButtonClicked(ActionEvent event) {
    NavigateEvent navigateEvent = new NavigateEvent((NavigationItem) ((Node) event.getSource()).getUserData());
    log.debug("Navigating to {}", navigateEvent.getItem().toString());
    eventBus.post(navigateEvent);
  }

  @Subscribe
  public void onNavigateEvent(NavigateEvent navigateEvent) {
    NavigationItem item = navigateEvent.getItem();

    AbstractViewController<?> controller = getView(item);
    displayView(controller, navigateEvent);

    mainNavigation.getToggles().stream()
        .filter(toggle -> toggle.getUserData() == navigateEvent.getItem())
        .findFirst()
        .ifPresent(toggle -> toggle.setSelected(true));

    currentItem = item;
  }

  private AbstractViewController<?> getView(NavigationItem item) {
    if (alwaysReloadTabs) {
      return uiService.loadFxml(item.getFxmlFile());
    }
    return noCatch(() -> viewCache.get(item, () -> uiService.loadFxml(item.getFxmlFile())));
  }

  public void onRevealGamePaths() {
    preferencesService.getPreferences().getTotalAnnihilationAllMods().forEach(
        (prefs) -> this.platformService.reveal(prefs.getInstalledExePath())
    );
  }

  public void onRevealLogFolder() {
    Path logPath = preferencesService.getFafLogDirectory();
    this.platformService.reveal(logPath);
  }

  public void onPlay(ActionEvent actionEvent) {
    playButton.pseudoClassStateChanged(HIGHLIGHTED, false);
    onNavigateButtonClicked(actionEvent);
  }

  public void onMatchmaker(ActionEvent actionEvent) {
    matchmakerButton.pseudoClassStateChanged(HIGHLIGHTED, false);
    onNavigateButtonClicked(actionEvent);
  }

  private void displayImmediateNotification(ImmediateNotification notification) {
    Alert<?> dialog = new Alert<>(fxStage.getStage());

    ImmediateNotificationController controller = ((ImmediateNotificationController) uiService.loadFxml("theme/immediate_notification.fxml"))
        .setNotification(notification)
        .setCloseListener(dialog::close);

    dialog.setContent(controller.getDialogLayout());
    dialog.setAnimation(AlertAnimation.TOP_ANIMATION);
    dialog.setOverlayClose(notification.isOverlayClose());
    dialog.setHideOnEscape(notification.isHideOnEscape());
    dialog.show();
  }

  private void displayServerNotification(ImmediateNotification notification) {
    Alert<?> dialog = new Alert<>(fxStage.getStage());

    ServerNotificationController controller = ((ServerNotificationController) uiService.loadFxml("theme/server_notification.fxml"))
        .setNotification(notification)
        .setCloseListener(dialog::close);

    dialog.setContent(controller.getDialogLayout());
    dialog.setAnimation(AlertAnimation.TOP_ANIMATION);
    dialog.show();
  }

  public void onLinksAndHelp() {
    LinksAndHelpController linksAndHelpController = uiService.loadFxml("theme/links_and_help.fxml");
    Node root = linksAndHelpController.getRoot();
    uiService.showInDialog(mainRoot, root, i18n.get("help.title"));

    root.requestFocus();
  }

  public void setFxStage(FxStage fxWindow) {
    this.fxStage = fxWindow;
  }

  public void onDiscordButtonClicked(MouseEvent event) {
    discordSelectionMenuController.getContextMenu().show(this.getRoot().getScene().getWindow(), event.getScreenX(), event.getScreenY());
  }

  public void onSubmitLogs(ActionEvent actionEvent) {
    log.info("[onSubmitLogs] submitting logs to TAF on user request");
    Path logGpgnet4ta = preferencesService.getMostRecentLogFile("game").orElse(Path.of(""));
    Path logLauncher = preferencesService.getMostRecentLogFile("talauncher").orElse(Path.of(""));
    Path logClient = preferencesService.getFafLogDirectory().resolve("client.log");
    Path logIceAdapter = preferencesService.getIceAdapterLogDirectory().resolve("ice-adapter.log");
    Path targetZipFile = preferencesService.getFafLogDirectory().resolve("logs.zip");
    try {
      File files[] = {logIceAdapter.toFile(), logClient.toFile(),logGpgnet4ta.toFile(), logLauncher.toFile()};
      ZipUtil.zipFile(files, targetZipFile.toFile());
      ResourceLocks.acquireUploadLock();
      fafService.uploadGameLogs(targetZipFile, "adhoc", 0, (written, total) -> {});
      notificationService.addImmediateInfoNotification("menu.submitLogs", "menu.submitLogs.done");
    } catch (Exception e) {
      log.error("[submitLogs] unable to submit logs:", e.getMessage());
      notificationService.addImmediateErrorNotification(new RuntimeException(e.getMessage()),"menu.submitLogs.failed");
    } finally {
      ResourceLocks.freeUploadLock();
      try { Files.delete(targetZipFile); } catch(Exception e) {}
    }
  }

  public class ToastDisplayer implements InvalidationListener {
    private final TransientNotificationsController transientNotificationsController;

    public ToastDisplayer(TransientNotificationsController transientNotificationsController) {
      this.transientNotificationsController = transientNotificationsController;
    }

    @Override
    public void invalidated(Observable observable) {
      boolean enabled = preferencesService.getPreferences().getNotification().isTransientNotificationsEnabled();
      if (transientNotificationsController.getRoot().getChildren().isEmpty() || !enabled) {
        transientNotificationsPopup.hide();
        return;
      }

      Rectangle2D visualBounds = getTransientNotificationAreaBounds();
      double anchorX = visualBounds.getMaxX() - 1;
      double anchorY = visualBounds.getMaxY() - 1;
      switch (preferencesService.getPreferences().getNotification().toastPositionProperty().get()) {
        case BOTTOM_RIGHT:
          transientNotificationsPopup.setAnchorLocation(AnchorLocation.CONTENT_BOTTOM_RIGHT);
          break;
        case TOP_RIGHT:
          transientNotificationsPopup.setAnchorLocation(AnchorLocation.CONTENT_TOP_RIGHT);
          anchorY = visualBounds.getMinY();
          break;
        case BOTTOM_LEFT:
          transientNotificationsPopup.setAnchorLocation(AnchorLocation.CONTENT_BOTTOM_LEFT);
          anchorX = visualBounds.getMinX();
          break;
        case TOP_LEFT:
          transientNotificationsPopup.setAnchorLocation(AnchorLocation.CONTENT_TOP_LEFT);
          anchorX = visualBounds.getMinX();
          anchorY = visualBounds.getMinY();
          break;
        default:
          transientNotificationsPopup.setAnchorLocation(AnchorLocation.CONTENT_BOTTOM_RIGHT);
          break;
      }
      transientNotificationsPopup.show(mainRoot.getScene().getWindow(), anchorX, anchorY);
    }
  }
}
