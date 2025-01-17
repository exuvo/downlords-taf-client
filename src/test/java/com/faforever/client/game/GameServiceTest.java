package com.faforever.client.game;

import com.faforever.client.chat.ChatService;
import com.faforever.client.config.ClientProperties;
import com.faforever.client.discord.DiscordRichPresenceService;
import com.faforever.client.fa.TotalAnnihilationService;
import com.faforever.client.fa.relay.LobbyMode;
import com.faforever.client.fa.relay.event.RehostRequestEvent;
import com.faforever.client.fa.relay.ice.IceAdapter;
import com.faforever.client.fx.PlatformService;
import com.faforever.client.i18n.I18n;
import com.faforever.client.map.MapService;
import com.faforever.client.mod.FeaturedMod;
import com.faforever.client.mod.ModService;
import com.faforever.client.notification.NotificationService;
import com.faforever.client.notification.PersistentNotification;
import com.faforever.client.patch.GameUpdater;
import com.faforever.client.player.Player;
import com.faforever.client.player.PlayerBuilder;
import com.faforever.client.player.PlayerService;
import com.faforever.client.preferences.Preferences;
import com.faforever.client.preferences.PreferencesBuilder;
import com.faforever.client.preferences.PreferencesService;
import com.faforever.client.remote.FafService;
import com.faforever.client.remote.ReconnectTimerService;
import com.faforever.client.remote.domain.GameInfoMessage;
import com.faforever.client.remote.domain.GameLaunchMessage;
import com.faforever.client.replay.ReplayServer;
import com.faforever.client.test.AbstractPlainJavaFxTest;
import com.faforever.client.ui.preferences.event.GameDirectoryChooseEvent;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableMap;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.util.ReflectionUtils;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.faforever.client.game.Faction.GOK;
import static com.faforever.client.remote.domain.GameStatus.ENDED;
import static com.faforever.client.remote.domain.GameStatus.STAGING;
import static com.faforever.client.remote.domain.GameStatus.LIVE;
import static com.natpryce.hamcrest.reflection.HasAnnotationMatcher.hasAnnotation;
import static java.util.Arrays.asList;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.collection.IsEmptyCollection.empty;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class GameServiceTest extends AbstractPlainJavaFxTest {

  private static final long TIMEOUT = 5000;
  private static final TimeUnit TIME_UNIT = TimeUnit.MILLISECONDS;
  private static final Integer GPG_PORT = 1234;
  private static final int LOCAL_REPLAY_PORT = 15111;
  private static final String GLOBAL_RATING_TYPE = "global";
  private static final String LADDER_1v1_RATING_TYPE = "ladder_1v1";

  private GameService instance;

  @Mock
  private PreferencesService preferencesService;
  @Mock
  private FafService fafService;
  @Mock
  private MapService mapService;
  @Mock
  private TotalAnnihilationService totalAnnihilationService;
  @Mock
  private GameUpdater gameUpdater;
  @Mock
  private PlayerService playerService;
  @Mock
  private ExecutorService executorService;
  @Mock
  private ReplayServer replayService;
  @Mock
  private EventBus eventBus;
  @Mock
  private IceAdapter iceAdapter;
  @Mock
  private ModService modService;
  @Mock
  private NotificationService notificationService;
  @Mock
  private I18n i18n;
  @Mock
  private ChatService chatService;
  @Mock
  private PlatformService platformService;
  @Mock
  private ReconnectTimerService reconnectTimerService;
  @Mock
  private DiscordRichPresenceService discordRichPresenceService;
  @Mock
  private Process process;

  @Captor
  private ArgumentCaptor<Consumer<GameInfoMessage>> gameInfoMessageListenerCaptor;
  @Captor
  private ArgumentCaptor<Set<String>> simModsCaptor;

  private Player junitPlayer;
  private Preferences preferences;

  @Before
  public void setUp() throws Exception {
    junitPlayer = PlayerBuilder.create("JUnit").defaultValues().get();
    preferences = PreferencesBuilder.create().defaultValues().get();

    ClientProperties clientProperties = new ClientProperties();

    when(preferencesService.getPreferences()).thenReturn(preferences);
    when(preferencesService.isGameExeValid(KnownFeaturedMod.DEFAULT.getTechnicalName())).thenReturn(true);
    when(fafService.connectionStateProperty()).thenReturn(new SimpleObjectProperty<>());
    when(replayService.start(anyInt(), any())).thenReturn(completedFuture(LOCAL_REPLAY_PORT));
    when(iceAdapter.start("BILY_IDOL")).thenReturn(completedFuture(GPG_PORT));
    when(playerService.getCurrentPlayer()).thenReturn(Optional.of(junitPlayer));

    doAnswer(invocation -> {
      try {
        ((Runnable) invocation.getArgument(0)).run();
      } catch (Exception e) {
        e.printStackTrace();
      }
      return null;
    }).when(executorService).execute(any());

    instance = new GameService(clientProperties, fafService, totalAnnihilationService, mapService,
        preferencesService, notificationService, i18n, executorService, playerService,
        eventBus, iceAdapter, modService, platformService, discordRichPresenceService,
        replayService, reconnectTimerService, chatService);

    instance.afterPropertiesSet();

    verify(fafService).addOnMessageListener(eq(GameInfoMessage.class), gameInfoMessageListenerCaptor.capture());
  }

  private void mockGlobalStartGameProcess(int uid, String... additionalArgs) throws IOException {
    mockStartGameProcess(uid, GLOBAL_RATING_TYPE, null, false, additionalArgs);
  }

  private void mockStartGameProcess(int uid, String ratingType, Faction faction, boolean rehost, String... additionalArgs) throws IOException {
    when(totalAnnihilationService.startGame(KnownFeaturedMod.DEFAULT.getTechnicalName(),
        uid, asList(additionalArgs), GPG_PORT, junitPlayer, null, null, true, "0.0.0.0")
    ).thenReturn(process);
  }

  private void mockStartReplayProcess(Path path, int id) throws IOException {
    when(totalAnnihilationService.startReplay(KnownFeaturedMod.DEFAULT.getTechnicalName(), "123456.tad", 123456, "BILLY_IDOL")).thenReturn(process);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void postConstruct() {
    verify(fafService).addOnMessageListener(eq(GameInfoMessage.class), any(Consumer.class));
  }

  @Test
  public void testJoinGameMapIsAvailable() throws Exception {
    Game game = GameBuilder.create().defaultValues().get();

    ObservableMap<String, String> simMods = FXCollections.observableHashMap();
    simMods.put("123-456-789", "Fake mod name");

    game.setSimMods(simMods);

    GameLaunchMessage gameLaunchMessage = GameLaunchMessageBuilder.create().defaultValues().get();

    mockGlobalStartGameProcess(gameLaunchMessage.getUid());
    when(mapService.isInstalled(game.getFeaturedMod(), game.getMapName(), game.getMapCrc())).thenReturn(true);
    when(fafService.requestJoinGame(game.getId(), null)).thenReturn(completedFuture(gameLaunchMessage));
    when(gameUpdater.update(any(), any(), any(), any())).thenReturn(completedFuture(null));
    when(modService.getFeaturedMod(game.getFeaturedMod())).thenReturn(completedFuture(FeaturedModBeanBuilder.create().defaultValues().get()));

    CompletableFuture<Void> future = instance.joinGame(game, null).toCompletableFuture();

    assertNull(future.get(TIMEOUT, TIME_UNIT));
    verify(mapService, never()).ensureMap(any(),any(),any(),any());
    verify(replayService).start(eq(game.getId()), any());

    verify(totalAnnihilationService).startGame(gameLaunchMessage.getMod(),
        gameLaunchMessage.getUid(), asList(), GPG_PORT, junitPlayer, null, null, true, "0.0.0.0");
  }

  @Test
  public void testJoinGameNoLaunchNoGameRatingType() throws Exception {
    Game game = GameBuilder.create().defaultValues().ratingType(null).get();

    GameLaunchMessage gameLaunchMessage = GameLaunchMessageBuilder.create().defaultValues().ratingType(null).get();

    mockGlobalStartGameProcess(gameLaunchMessage.getUid());
    when(mapService.isInstalled(game.getFeaturedMod(), game.getMapName(), game.getMapCrc())).thenReturn(true);
    when(fafService.requestJoinGame(game.getId(), null)).thenReturn(completedFuture(gameLaunchMessage));
    when(gameUpdater.update(any(), any(), any(), any())).thenReturn(completedFuture(null));
    when(modService.getFeaturedMod(game.getFeaturedMod())).thenReturn(completedFuture(FeaturedModBeanBuilder.create().defaultValues().get()));

    CompletableFuture<Void> future = instance.joinGame(game, null).toCompletableFuture();

    assertNull(future.get(TIMEOUT, TIME_UNIT));
    verify(mapService, never()).ensureMap(any(),any(),any(),any());
    verify(replayService).start(eq(game.getId()), any());

    verify(totalAnnihilationService).startGame(
        gameLaunchMessage.getMod(), gameLaunchMessage.getUid(), gameLaunchMessage.getArgs(),
        GPG_PORT, junitPlayer, null, null, true, "0.0.0.0");
  }

  @Test
  public void testJoinGameNoLaunchRatingType() throws Exception {
    Game game = GameBuilder.create().defaultValues().get();

    GameLaunchMessage gameLaunchMessage = GameLaunchMessageBuilder.create().defaultValues().ratingType(null).get();

    mockGlobalStartGameProcess(gameLaunchMessage.getUid());
    when(mapService.isInstalled(game.getFeaturedMod(), game.getMapName(), game.getMapCrc())).thenReturn(true);
    when(fafService.requestJoinGame(game.getId(), null)).thenReturn(completedFuture(gameLaunchMessage));
    when(gameUpdater.update(any(), any(), any(), any())).thenReturn(completedFuture(null));
    when(modService.getFeaturedMod(game.getFeaturedMod())).thenReturn(completedFuture(FeaturedModBeanBuilder.create().defaultValues().get()));

    CompletableFuture<Void> future = instance.joinGame(game, null).toCompletableFuture();

    assertNull(future.get(TIMEOUT, TIME_UNIT));
    verify(mapService, never()).downloadAndInstallArchive(KnownFeaturedMod.DEFAULT.getTechnicalName(),any());
    verify(replayService).start(eq(game.getId()), any());

    verify(totalAnnihilationService).startGame(KnownFeaturedMod.DEFAULT.getTechnicalName(),
        gameLaunchMessage.getUid(), asList(), GPG_PORT, junitPlayer, null, null, false, "0.0.0.0");
  }

  @Test
  public void testStartReplayWhileInGameNotAllowed() throws Exception {
    Game game = GameBuilder.create().defaultValues().get();

    GameLaunchMessage gameLaunchMessage = GameLaunchMessageBuilder.create().defaultValues().get();
    Path replayPath = Paths.get("temp.scfareplay");
    int replayId = 1234;

    mockGlobalStartGameProcess(gameLaunchMessage.getUid());
    when(mapService.isInstalled(anyString(),anyString(),anyString())).thenReturn(true);
    when(fafService.requestJoinGame(anyInt(), isNull())).thenReturn(completedFuture(gameLaunchMessage));
    when(gameUpdater.update(any(), any(), any(), any())).thenReturn(completedFuture(null));
    when(modService.getFeaturedMod(anyString())).thenReturn(completedFuture(FeaturedModBeanBuilder.create().defaultValues().get()));
    when(process.isAlive()).thenReturn(true);

    CompletableFuture<Void> future = instance.joinGame(game, null).toCompletableFuture();
    future.join();
    mockStartReplayProcess(replayPath, replayId);
    future = instance.runWithReplay(replayPath.toString(), replayId, "", null, null, null);
    future.join();

    verify(replayService).start(eq(game.getId()), any());
    verify(totalAnnihilationService).startGame(gameLaunchMessage.getMod(),
        gameLaunchMessage.getUid(), asList(), GPG_PORT, junitPlayer, null, null, false, "0.0.0.0");
    verify(totalAnnihilationService, never()).startReplay(KnownFeaturedMod.TACC.getTechnicalName(), replayPath.toString(), replayId, "BILLY_IDOL");
  }

  @Test
  public void testModEnabling() throws Exception {
    Game game = GameBuilder.create().defaultValues().get();

    ObservableMap<String, String> simMods = FXCollections.observableHashMap();
    simMods.put("123-456-789", "Fake mod name");

    game.setSimMods(simMods);
    game.setMapName("map");
    game.setMapCrc("12345678");

    GameLaunchMessage gameLaunchMessage = GameLaunchMessageBuilder.create().defaultValues().get();

    mockGlobalStartGameProcess(gameLaunchMessage.getUid());
    when(mapService.isInstalled("tacc","map", "12345678")).thenReturn(true);
    when(fafService.requestJoinGame(game.getId(), null)).thenReturn(completedFuture(gameLaunchMessage));
    when(gameUpdater.update(any(), any(), any(), any())).thenReturn(completedFuture(null));
    when(modService.getFeaturedMod(game.getFeaturedMod())).thenReturn(completedFuture(FeaturedModBeanBuilder.create().defaultValues().get()));

    instance.joinGame(game, null).toCompletableFuture().get();
    assertEquals(simModsCaptor.getValue().iterator().next(), "123-456-789");
  }

  @Test
  public void testAddOnGameStartedListener() throws Exception {
    NewGameInfo newGameInfo = NewGameInfoBuilder.create().defaultValues().get();
    GameLaunchMessage gameLaunchMessage = GameLaunchMessageBuilder.create().defaultValues().args("/foo bar", "/bar foo").get();

    mockGlobalStartGameProcess(gameLaunchMessage.getUid(), "/foo", "bar", "/bar", "foo");
    when(gameUpdater.update(any(), any(), any(), any())).thenReturn(completedFuture(null));
    when(fafService.requestHostGame(newGameInfo)).thenReturn(completedFuture(gameLaunchMessage));
    when(mapService.downloadAndInstallArchive(KnownFeaturedMod.DEFAULT.getTechnicalName(), newGameInfo.getMap())).thenReturn(completedFuture(null));

    CountDownLatch gameStartedLatch = new CountDownLatch(1);
    CountDownLatch gameTerminatedLatch = new CountDownLatch(1);
    instance.runningGameUidProperty().addListener((observable, oldValue, newValue) -> {
      if (newValue != null) {
        gameStartedLatch.countDown();
      } else {
        gameTerminatedLatch.countDown();
      }
    });

    CountDownLatch processLatch = new CountDownLatch(1);

    instance.hostGame(newGameInfo).toCompletableFuture().get(TIMEOUT, TIME_UNIT);
    gameStartedLatch.await(TIMEOUT, TIME_UNIT);
    processLatch.countDown();

    gameTerminatedLatch.await(TIMEOUT, TIME_UNIT);
    verify(totalAnnihilationService).startGame(KnownFeaturedMod.DEFAULT.getTechnicalName(),
        gameLaunchMessage.getUid(), asList("/foo", "bar", "/bar", "foo"), GPG_PORT, junitPlayer, null, null, false, "0.0.0.0");
    verify(replayService).start(eq(gameLaunchMessage.getUid()), any());
  }

  @Test
  public void testWaitForProcessTerminationInBackground() throws Exception {
    instance.runningGameUidProperty.set(123456);

    CompletableFuture<Void> disconnectedFuture = new CompletableFuture<>();

    instance.runningGameUidProperty().addListener((observable, oldValue, newValue) -> {
      if (newValue == null) {
        disconnectedFuture.complete(null);
      }
    });

    Process process = mock(Process.class);

    instance.spawnGenericTerminationListener(process);

    disconnectedFuture.get(5000, TimeUnit.MILLISECONDS);

    verify(process).waitFor();
  }

  @Test
  public void testOnGames() {
    assertThat(instance.getGames(), empty());

    GameInfoMessage multiGameInfoMessage = new GameInfoMessage();
    multiGameInfoMessage.setGames(asList(
        GameInfoMessageBuilder.create(1).defaultValues().get(),
        GameInfoMessageBuilder.create(2).defaultValues().get()
    ));

    gameInfoMessageListenerCaptor.getValue().accept(multiGameInfoMessage);
    WaitForAsyncUtils.waitForFxEvents();

    assertThat(instance.getGames(), hasSize(2));
  }

  @Test
  public void testOnGameInfoAdd() {
    assertThat(instance.getGames(), empty());

    GameInfoMessage gameInfoMessage1 = GameInfoMessageBuilder.create(1).defaultValues().title("Game 1").get();
    gameInfoMessageListenerCaptor.getValue().accept(gameInfoMessage1);

    GameInfoMessage gameInfoMessage2 = GameInfoMessageBuilder.create(2).defaultValues().title("Game 2").get();
    gameInfoMessageListenerCaptor.getValue().accept(gameInfoMessage2);
    WaitForAsyncUtils.waitForFxEvents();

    assertThat(instance.getGames(), containsInAnyOrder(
        allOf(
            GameMatchers.hasId(1),
            GameMatchers.hasTitle("Game 1")
        ),
        allOf(
            GameMatchers.hasId(2),
            GameMatchers.hasTitle("Game 2")
        )
    ));
  }

  @Test
  public void testOnGameInfoMessageSetsCurrentGameIfUserIsInAndStatusOpen() {
    assertThat(instance.getCurrentGame(), nullValue());

    when(playerService.getCurrentPlayer()).thenReturn(Optional.ofNullable(PlayerBuilder.create("PlayerName").get()));

    GameInfoMessage gameInfoMessage = GameInfoMessageBuilder.create(1234).defaultValues()
        .state(STAGING)
        .addTeamMember("1", "PlayerName").get();
    gameInfoMessageListenerCaptor.getValue().accept(gameInfoMessage);
    WaitForAsyncUtils.waitForFxEvents();

    assertThat(instance.getCurrentGame(), notNullValue());
    assertThat(instance.getCurrentGame().getId(), is(1234));
  }

  @Test
  public void testOnGameInfoMessageDoesntSetCurrentGameIfUserIsInAndStatusNotOpen() {
    assertThat(instance.getCurrentGame(), nullValue());

    when(playerService.getCurrentPlayer()).thenReturn(Optional.ofNullable(PlayerBuilder.create("PlayerName").get()));

    GameInfoMessage gameInfoMessage = GameInfoMessageBuilder.create(1234).defaultValues()
        .state(LIVE)
        .addTeamMember("1", "PlayerName").get();
    gameInfoMessageListenerCaptor.getValue().accept(gameInfoMessage);

    assertThat(instance.getCurrentGame(), nullValue());
  }

  @Test
  public void testOnGameInfoMessageDoesntSetCurrentGameIfUserDoesntMatch() {
    assertThat(instance.getCurrentGame(), nullValue());

    when(playerService.getCurrentPlayer()).thenReturn(Optional.ofNullable(PlayerBuilder.create("PlayerName").get()));

    GameInfoMessage gameInfoMessage = GameInfoMessageBuilder.create(1234).defaultValues().addTeamMember("1", "Other").get();
    gameInfoMessageListenerCaptor.getValue().accept(gameInfoMessage);

    assertThat(instance.getCurrentGame(), nullValue());
  }

  @Test
  public void testOnGameInfoModify() throws InterruptedException {
    assertThat(instance.getGames(), empty());

    GameInfoMessage gameInfoMessage = GameInfoMessageBuilder.create(1).defaultValues().title("Game 1").state(LIVE).get();
    gameInfoMessageListenerCaptor.getValue().accept(gameInfoMessage);
    WaitForAsyncUtils.waitForFxEvents();

    CountDownLatch changeLatch = new CountDownLatch(1);
    Game game = instance.getGames().iterator().next();
    game.titleProperty().addListener((observable, oldValue, newValue) -> {
      changeLatch.countDown();
    });

    gameInfoMessage = GameInfoMessageBuilder.create(1).defaultValues().title("Game 1 modified").state(LIVE).get();
    gameInfoMessageListenerCaptor.getValue().accept(gameInfoMessage);

    changeLatch.await();
    assertEquals(gameInfoMessage.getTitle(), game.getTitle());
  }

  @Test
  public void testOnGameInfoRemove() {
    assertThat(instance.getGames(), empty());

    when(playerService.getCurrentPlayer()).thenReturn(Optional.ofNullable(PlayerBuilder.create("PlayerName").get()));

    GameInfoMessage gameInfoMessage = GameInfoMessageBuilder.create(1).defaultValues().title("Game 1").get();
    gameInfoMessageListenerCaptor.getValue().accept(gameInfoMessage);

    gameInfoMessage = GameInfoMessageBuilder.create(1).title("Game 1").defaultValues().state(ENDED).get();
    gameInfoMessageListenerCaptor.getValue().accept(gameInfoMessage);
    WaitForAsyncUtils.waitForFxEvents();

    assertThat(instance.getGames(), empty());
  }

  @Test
  public void testStartSearchLadder1v1() throws Exception {
    int uid = 123;
    String map = "scmp_037";
    GameLaunchMessage gameLaunchMessage = new GameLaunchMessageBuilder().defaultValues()
        .uid(uid).mod("FAF").mapname(map)
        .expectedPlayers(2)
        .faction(GOK)
        .initMode(LobbyMode.AUTO_LOBBY)
        .mapPosition(4)
        .team(1)
        .ratingType(LADDER_1v1_RATING_TYPE)
        .get();

    FeaturedMod featuredMod = FeaturedModBeanBuilder.create().defaultValues().get();

    String[] additionalArgs = {"/team", "1", "/players", "2", "/startspot", "4"};
    mockStartGameProcess(uid, LADDER_1v1_RATING_TYPE, GOK, false, additionalArgs);
    when(fafService.startSearchMatchmaker()).thenReturn(completedFuture(gameLaunchMessage));
    when(gameUpdater.update(featuredMod, null, Collections.emptyMap(), Collections.emptySet())).thenReturn(completedFuture(null));
    when(mapService.isInstalled("tacc", map, "00000000")).thenReturn(false);
    when(mapService.downloadAndInstallArchive(KnownFeaturedMod.DEFAULT.getTechnicalName(),map)).thenReturn(completedFuture(null));
    when(modService.getFeaturedMod(KnownFeaturedMod.TACC.getTechnicalName())).thenReturn(completedFuture(featuredMod));

    instance.startSearchMatchmaker(KnownFeaturedMod.TACC.getTechnicalName()).toCompletableFuture();

    verify(fafService).startSearchMatchmaker();
    verify(mapService).downloadAndInstallArchive(KnownFeaturedMod.DEFAULT.getTechnicalName(),map);
    verify(replayService).start(eq(uid), any());
    verify(totalAnnihilationService).startGame(KnownFeaturedMod.DEFAULT.getTechnicalName(),
        uid, asList(additionalArgs), GPG_PORT, junitPlayer, null, null, false, "0.0.0.0");
  }

  @Test
  public void testStartSearchMatchmakerGameRunningDoesNothing() throws Exception {
    Process process = mock(Process.class);
    when(process.isAlive()).thenReturn(true);

    NewGameInfo newGameInfo = NewGameInfoBuilder.create().defaultValues().get();
    GameLaunchMessage gameLaunchMessage = GameLaunchMessageBuilder.create().defaultValues().get();

    when(totalAnnihilationService.startGame(KnownFeaturedMod.DEFAULT.getTechnicalName(),anyInt(), any(), anyInt(), eq(junitPlayer), any(), any(), eq(false), any())).thenReturn(process);
    when(gameUpdater.update(any(), any(), any(), any())).thenReturn(completedFuture(null));
    when(fafService.requestHostGame(newGameInfo)).thenReturn(completedFuture(gameLaunchMessage));
    when(mapService.downloadAndInstallArchive(KnownFeaturedMod.DEFAULT.getTechnicalName(),newGameInfo.getMap())).thenReturn(completedFuture(null));

    CountDownLatch gameRunningLatch = new CountDownLatch(1);
    instance.runningGameUidProperty().addListener((observable, oldValue, newValue) -> {
      if (newValue != null) {
        gameRunningLatch.countDown();
      }
    });

    instance.hostGame(newGameInfo);
    gameRunningLatch.await(TIMEOUT, TIME_UNIT);

    instance.startSearchMatchmaker(KnownFeaturedMod.DEFAULT.getTechnicalName());

    assertThat(instance.getInMatchmakerQueueProperty().get(), is(false));
  }

  @Test
  public void testStopSearchMatchmaker() {
    instance.getInMatchmakerQueueProperty().set(true);
    instance.onMatchmakerSearchStopped();
    assertThat(instance.getInMatchmakerQueueProperty().get(), is(false));
    verify(fafService).stopSearchMatchmaker();
  }

  @Test
  public void testStopSearchMatchmakerNotSearching() {
    instance.getInMatchmakerQueueProperty().set(false);
    instance.onMatchmakerSearchStopped();
    assertThat(instance.getInMatchmakerQueueProperty().get(), is(false));
    verify(fafService, never()).stopSearchMatchmaker();
  }

  @Test
  public void testSubscribeEventBus() {
    verify(eventBus).register(instance);

    assertThat(ReflectionUtils.findMethod(
        instance.getClass(), "onRehostRequest", RehostRequestEvent.class),
        hasAnnotation(Subscribe.class));
  }

  @Test
  public void testRehostIfGameIsNotRunning() throws Exception {
    Game game = GameBuilder.create().defaultValues().get();
    instance.currentGame.set(game);

    mockStartGameProcess(game.getId(), GLOBAL_RATING_TYPE, null, true);
    when(modService.getFeaturedMod(game.getFeaturedMod())).thenReturn(completedFuture(FeaturedModBeanBuilder.create().defaultValues().get()));
    when(gameUpdater.update(any(), any(), any(), any())).thenReturn(completedFuture(null));
    when(fafService.requestHostGame(any())).thenReturn(completedFuture(GameLaunchMessageBuilder.create().defaultValues().get()));
    when(modService.getFeaturedMod(game.getFeaturedMod())).thenReturn(completedFuture(FeaturedModBeanBuilder.create().defaultValues().get()));
    when(mapService.downloadAndInstallArchive(KnownFeaturedMod.DEFAULT.getTechnicalName(),game.getMapName())).thenReturn(completedFuture(null));

    instance.onRehostRequest(new RehostRequestEvent(game));

    verify(totalAnnihilationService).startGame(KnownFeaturedMod.DEFAULT.getTechnicalName(),anyInt(), eq(null), anyList(), eq(GLOBAL_RATING_TYPE), anyInt(), eq(LOCAL_REPLAY_PORT), eq(true), eq(junitPlayer));
  }

  @Test
  public void testRehostIfGameIsRunning() throws Exception {
    instance.runningGameUidProperty.set(true);

    Game game = GameBuilder.create().defaultValues().get();
    instance.currentGame.set(game);

    instance.onRehostRequest(new RehostRequestEvent());

    verify(totalAnnihilationService, never()).startGame(KnownFeaturedMod.DEFAULT.getTechnicalName(),anyInt(), any(), any(), any(), anyInt(), eq(LOCAL_REPLAY_PORT), anyBoolean(), eq(junitPlayer));
  }

  @Test
  public void testCurrentGameEndedBehaviour() {
    Game game = new Game();
    game.setId(123);
    game.setStatus(LIVE);

    instance.currentGame.set(game);

    verify(notificationService, never()).addNotification(any(PersistentNotification.class));

    game.setStatus(LIVE);
    game.setStatus(ENDED);

    WaitForAsyncUtils.waitForFxEvents();
    verify(notificationService).addNotification(any(PersistentNotification.class));
  }

  @Test
  public void testGameHostIfNoGameSet() {
    when(preferencesService.isGameExeValid(KnownFeaturedMod.DEFAULT.getTechnicalName())).thenReturn(false);
    instance.hostGame(null);
    verify(eventBus).post(any(GameDirectoryChooseEvent.class));
  }

  @Test
  public void testGameHost() throws IOException {
    when(preferencesService.isGamePathValid()).thenReturn(true);
    NewGameInfo newGameInfo = NewGameInfoBuilder.create().defaultValues().get();
    GameLaunchMessage gameLaunchMessage = GameLaunchMessageBuilder.create().defaultValues().get();
    when(gameUpdater.update(newGameInfo.getFeaturedMod(), null, Map.of(), newGameInfo.getSimMods())).thenReturn(completedFuture(null));
    when(mapService.download(newGameInfo.getMap())).thenReturn(completedFuture(null));
    when(fafService.requestHostGame(newGameInfo)).thenReturn(completedFuture(gameLaunchMessage));
    instance.hostGame(newGameInfo);
    verify(totalAnnihilationService).startGame(
        gameLaunchMessage.getUid(), null, List.of(), gameLaunchMessage.getRatingType(),
        GPG_PORT, LOCAL_REPLAY_PORT, false, junitPlayer);
  }

  @Test
  public void testGameHostNoGameLaunchRatingType() throws IOException {
    when(preferencesService.isGamePathValid()).thenReturn(true);
    NewGameInfo newGameInfo = NewGameInfoBuilder.create().defaultValues().get();
    GameLaunchMessage gameLaunchMessage = GameLaunchMessageBuilder.create().defaultValues().ratingType(null).get();
    when(gameUpdater.update(newGameInfo.getFeaturedMod(), null, Map.of(), newGameInfo.getSimMods())).thenReturn(completedFuture(null));
    when(mapService.download(newGameInfo.getMap())).thenReturn(completedFuture(null));
    when(fafService.requestHostGame(newGameInfo)).thenReturn(completedFuture(gameLaunchMessage));
    instance.hostGame(newGameInfo);
    verify(totalAnnihilationService).startGame(
        gameLaunchMessage.getUid(), null, List.of(), GameService.DEFAULT_RATING_TYPE,
        GPG_PORT, LOCAL_REPLAY_PORT, false, junitPlayer);
  }

  @Test
  public void runWithLiveReplayIfNoGameSet() {
    when(preferencesService.isGameExeValid(KnownFeaturedMod.DEFAULT.getTechnicalName())).thenReturn(false);
    instance.runWithReplay(null, null, null, null, null, null);
    verify(eventBus).post(any(GameDirectoryChooseEvent.class));
  }

  @Test
  public void startSearchMatchmakerIfNoGameSet() {
    when(preferencesService.isGameExeValid(KnownFeaturedMod.DEFAULT.getTechnicalName())).thenReturn(false);
    instance.startSearchMatchmaker();
    verify(eventBus).post(any(GameDirectoryChooseEvent.class));
  }

  @Test
  public void startSearchMatchmaker() throws IOException {
    when(preferencesService.isGamePathValid()).thenReturn(true);
    when(modService.getFeaturedMod(FAF.getTechnicalName()))
        .thenReturn(completedFuture(FeaturedModBeanBuilder.create().defaultValues().get()));
    GameLaunchMessage gameLaunchMessage = GameLaunchMessageBuilder.create().defaultValues().get();
    when(gameUpdater.update(any(), any(), any(), any())).thenReturn(completedFuture(null));
    when(mapService.download(gameLaunchMessage.getMapname())).thenReturn(completedFuture(null));
    when(fafService.startSearchMatchmaker()).thenReturn(completedFuture(gameLaunchMessage));
    instance.startSearchMatchmaker();
    verify(totalAnnihilationService).startGame(
        gameLaunchMessage.getUid(), null, List.of("/team", "null", "/players", "null", "/startspot", "null"),
        gameLaunchMessage.getRatingType(), GPG_PORT, LOCAL_REPLAY_PORT, false, junitPlayer);
  }

  @Test
  public void startSearchMatchmakerNoLaunchRatingType() throws IOException {
    when(preferencesService.isGamePathValid()).thenReturn(true);
    when(modService.getFeaturedMod(FAF.getTechnicalName()))
        .thenReturn(completedFuture(FeaturedModBeanBuilder.create().defaultValues().get()));
    GameLaunchMessage gameLaunchMessage = GameLaunchMessageBuilder.create().defaultValues().ratingType(null).get();
    when(gameUpdater.update(any(), any(), any(), any())).thenReturn(completedFuture(null));
    when(mapService.download(gameLaunchMessage.getMapname())).thenReturn(completedFuture(null));
    when(fafService.startSearchMatchmaker()).thenReturn(completedFuture(gameLaunchMessage));
    instance.setMatchedQueueRatingType("ladder_1v1");
    instance.startSearchMatchmaker();
    verify(totalAnnihilationService).startGame(
        gameLaunchMessage.getUid(), null, List.of("/team", "null", "/players", "null", "/startspot", "null"),
        "ladder_1v1", GPG_PORT, LOCAL_REPLAY_PORT, false, junitPlayer);
  }

  @Test
  public void startSearchMatchmakerNoLaunchRatingTypeNoQueueRatingType() throws IOException {
    when(preferencesService.isGamePathValid()).thenReturn(true);
    when(modService.getFeaturedMod(FAF.getTechnicalName()))
        .thenReturn(completedFuture(FeaturedModBeanBuilder.create().defaultValues().get()));
    GameLaunchMessage gameLaunchMessage = GameLaunchMessageBuilder.create().defaultValues().ratingType(null).get();
    when(gameUpdater.update(any(), any(), any(), any())).thenReturn(completedFuture(null));
    when(mapService.download(gameLaunchMessage.getMapname())).thenReturn(completedFuture(null));
    when(fafService.startSearchMatchmaker()).thenReturn(completedFuture(gameLaunchMessage));
    instance.setMatchedQueueRatingType(null);
    instance.startSearchMatchmaker();
    verify(totalAnnihilationService).startGame(
        gameLaunchMessage.getUid(), null, List.of("/team", "null", "/players", "null", "/startspot", "null"),
        GameService.DEFAULT_RATING_TYPE, GPG_PORT, LOCAL_REPLAY_PORT, false, junitPlayer);
  }

  @Test
  public void joinGameIfNoGameSet() {
    when(preferencesService.isGameExeValid(KnownFeaturedMod.DEFAULT.getTechnicalName())).thenReturn(false);
    instance.joinGame(null, null);
    verify(eventBus).post(any(GameDirectoryChooseEvent.class));
  }

  @Test
  public void runWithReplayIfNoGameSet() {
    when(preferencesService.isGameExeValid(KnownFeaturedMod.DEFAULT.getTechnicalName())).thenReturn(false);
    instance.runWithReplay(null, null, null, null, null, null, null);
    verify(eventBus).post(any(GameDirectoryChooseEvent.class));
  }

  @Test
  public void runWithReplayInMatchmakerQueue() {
    instance.getInMatchmakerQueueProperty().setValue(true);
    instance.runWithReplay(null, null, null, null, null, null, null);
    WaitForAsyncUtils.waitForFxEvents();
    verify(notificationService).addImmediateWarnNotification("replay.inQueue");
  }

  @Test
  public void runWithLiveReplayInMatchmakerQueue() {
    instance.getInMatchmakerQueueProperty().setValue(true);
    instance.runWithReplay(null, null, null, null, null, null);
    WaitForAsyncUtils.waitForFxEvents();
    verify(notificationService).addImmediateWarnNotification("replay.inQueue");
  }

  @Test
  public void runWithReplayInParty() {
    instance.getInOthersPartyProperty().setValue(true);
    instance.runWithReplay(null, null, null, null, null, null, null);
    WaitForAsyncUtils.waitForFxEvents();
    verify(notificationService).addImmediateWarnNotification("replay.inParty");
  }

  @Test
  public void runWithLiveReplayInParty() {
    instance.getInOthersPartyProperty().setValue(true);
    instance.runWithReplay(null, null, null, null, null, null);
    WaitForAsyncUtils.waitForFxEvents();
    verify(notificationService).addImmediateWarnNotification("replay.inParty");
  }

  @Test
  public void launchTutorialIfNoGameSet() {
    when(preferencesService.isGamePathValid()).thenReturn(false);
    instance.launchTutorial(null, null);
    verify(eventBus).post(any(GameDirectoryChooseEvent.class));
  }

  @Test
  public void iceCloseOnError() throws Exception {
    Game game = GameBuilder.create().defaultValues().get();

    game.setMapName("map");
    game.setMapCrc("12345678");

    GameLaunchMessage gameLaunchMessage = GameLaunchMessageBuilder.create().defaultValues().get();

    mockGlobalStartGameProcess(gameLaunchMessage.getUid());
    when(mapService.isInstalled("tacc","map", "12345678")).thenReturn(true);
    when(fafService.requestJoinGame(game.getId(), null)).thenReturn(completedFuture(gameLaunchMessage));
    when(gameUpdater.update(any(), any(), any(), any())).thenReturn(completedFuture(null));
    when(modService.getFeaturedMod(game.getFeaturedMod())).thenReturn(completedFuture(FeaturedModBeanBuilder.create().defaultValues().get()));
    when(replayService.start(anyInt(), any(Supplier.class))).thenReturn(completedFuture(new Throwable()));

    CompletableFuture<Void> future = instance.joinGame(game, null).toCompletableFuture();

    assertNull(future.get(TIMEOUT, TIME_UNIT));
    verify(mapService, never()).downloadAndInstallArchive(KnownFeaturedMod.DEFAULT.getTechnicalName(), any());
    verify(replayService).start(eq(game.getId()), any());
    verify(iceAdapter).stop();
  }
}
