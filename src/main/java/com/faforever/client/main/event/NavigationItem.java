package com.faforever.client.main.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.HashMap;

@RequiredArgsConstructor
@Getter
public enum NavigationItem {
  NEWS("theme/news.fxml", "main.news"),
  PLAY("theme/play/play.fxml", "main.play"),
  MATCHMAKER("theme/play/team_matchmaking.fxml", "main.matchmaker"),
  REPLAY("theme/vault/replay.fxml", "main.replay"),
  MAP("theme/vault/map.fxml", "main.maps"),
  MOD("theme/vault/mod.fxml", "main.mods"),
  LEADERBOARD("theme/leaderboard/leaderboards.fxml", "main.leaderboards"),
  UNITS("theme/units.fxml", "main.units"),
  TUTORIALS("theme/tutorial.fxml", "main.tutorials"),
  TOURNAMENTS("theme/tournaments/tournaments.fxml", "main.tournaments"),
  TADA("theme/tada.fxml", "main.tada");

  private static final HashMap<String, NavigationItem> fromString;

  static {
    fromString = new HashMap<>();
    for (NavigationItem item : values()) {
      fromString.put(item.name(), item);
    }
  }

  private final String fxmlFile;
  private final String i18nKey;

  public static NavigationItem fromString(String string) {
    if (string == null) {
      return NEWS;
    }
    return fromString.get(string);
  }

}
