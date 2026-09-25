package dev.thource.runelite.partnerslayer;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.MenuAction;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

public class PartnerSlayerOverlay extends OverlayPanel {
  private static final String RESET = "Reset";

  private final PartnerSlayerPlugin plugin;

  @Inject
  private PartnerSlayerOverlay(PartnerSlayerPlugin plugin) {
    super(plugin);
    setPosition(OverlayPosition.BOTTOM_LEFT);
    this.plugin = plugin;
    addMenuEntry(
        MenuAction.RUNELITE_OVERLAY_CONFIG,
        OverlayManager.OPTION_CONFIGURE,
        "Partner Slayer overlay");
    //    addMenuEntry(MenuAction.RUNELITE_OVERLAY, RESET, "Partner Slayer overlay", e ->
    // plugin.setSession(null));
  }

  @Override
  public Dimension render(Graphics2D graphics) {
    var slayerTask = plugin.getSlayerTask();
    //    CookingSession session = plugin.getSession();
    //    if (session == null)
    //    {
    //      return null;
    //    }

    var taskInitialAmount = slayerTask == null ? -1 : slayerTask.getInitialAmount();
    var ownKills = slayerTask == null ? -1 : slayerTask.getOwnKills();
    var ownKillsPercent = taskInitialAmount > 0 ? ownKills * 100 / taskInitialAmount : -1;
    var ownKillsColor =
        slayerTask == null ? Color.WHITE : (ownKillsPercent >= 20 ? Color.GREEN : Color.RED);
    var partnerKills = slayerTask == null ? -1 : slayerTask.getPartnerKills();
    var partnerKillsPercent = taskInitialAmount > 0 ? partnerKills * 100 / taskInitialAmount : -1;
    var partnerKillsColor =
        slayerTask == null ? Color.WHITE : (partnerKillsPercent >= 20 ? Color.GREEN : Color.RED);
    var partnerDistance = plugin.getPartnerDistance();
    var partnerDistanceColor =
        partnerDistance == -1 ? Color.WHITE : (partnerDistance < 30 ? Color.GREEN : Color.RED);

    panelComponent
        .getChildren()
        .add(TitleComponent.builder().text("Partner Slayer").color(Color.WHITE).build());

    panelComponent
        .getChildren()
        .add(
            LineComponent.builder()
                .left("Partner:")
                .right(plugin.getPartnerName() == null ? "-" : plugin.getPartnerName())
                .rightColor(Color.WHITE)
                .build());

    panelComponent
        .getChildren()
        .add(
            LineComponent.builder()
                .left("Task amount:")
                .right(taskInitialAmount == -1 ? "-" : String.valueOf(taskInitialAmount))
                .rightColor(Color.WHITE)
                .build());

    panelComponent
        .getChildren()
        .add(
            LineComponent.builder()
                .left("Your kills:")
                .right(ownKills == -1 ? "-" : ownKills + " (" + ownKillsPercent + "%)")
                .rightColor(ownKillsColor)
                .build());

    panelComponent
        .getChildren()
        .add(
            LineComponent.builder()
                .left("Partner kills:")
                .right(partnerKills == -1 ? "-" : partnerKills + " (" + partnerKillsPercent + "%)")
                .rightColor(partnerKillsColor)
                .build());

    panelComponent
        .getChildren()
        .add(
            LineComponent.builder()
                .left("Partner distance:")
                .right(partnerDistance == -1 ? "-" : String.valueOf(partnerDistance))
                .rightColor(partnerDistanceColor)
                .build());

    if (plugin.getPartnerMemberId() == -1L) {
      panelComponent
          .getChildren()
          .add(
              LineComponent.builder()
                  .left("Not connected, ensure partner is in your party and has the plugin.")
                  .leftColor(Color.RED)
                  .build());
    }

    return super.render(graphics);
  }
}
