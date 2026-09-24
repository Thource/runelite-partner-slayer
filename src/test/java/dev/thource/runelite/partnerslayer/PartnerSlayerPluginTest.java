package dev.thource.runelite.partnerslayer;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class PartnerSlayerPluginTest {

  @SuppressWarnings("unchecked")
  public static void main(String[] args) throws Exception {
    ExternalPluginManager.loadBuiltin(PartnerSlayerPlugin.class);
    RuneLite.main(args);
  }
}
