package dev.thource.runelite.partnerslayer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

/** PartnerSlayerConfig manages the config for the plugin. */
@SuppressWarnings("SameReturnValue")
@ConfigGroup("partnerSlayer")
public interface PartnerSlayerConfig extends Config {

  String CONFIG_GROUP = "partnerSlayer";

  @ConfigItem(
      keyName = "timeout",
      name = "Overlay timeout",
      description =
          "The time in minutes before the overlay disappears after receiving a new partner slayer"
              + " task or being near your task monsters.")
  @Range(min = 1, max = 999)
  default int timeout() {
    return 5;
  }
}
