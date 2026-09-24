package dev.thource.runelite.partnerslayer;

import com.google.inject.Provides;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.events.UserJoin;
import net.runelite.client.party.events.UserPart;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.party.messages.LocationUpdate;
import net.runelite.client.plugins.slayer.SlayerPlugin;
import net.runelite.client.plugins.slayer.SlayerPluginService;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * PartnerSlayerPlugin is a RuneLite plugin designed to enhance the experience of partner slayer.
 */
@Slf4j
@PluginDescriptor(
    name = "Partner Slayer",
    description = "Enhances the experience of partner slayer.",
    tags = {"partner", "slayer", "coop", "co-op"})
@PluginDependency(SlayerPlugin.class)
public class PartnerSlayerPlugin extends Plugin {

  private static final List<Pattern> PARTNER_TASK_PATTERNS =
      List.of(
          Pattern.compile("You have received a new Slayer assignment from ([^:]*):"),
          Pattern.compile("(.*) has been assigned to slay"));

  @Getter @Inject private Client client;
  @Getter @Inject private ClientThread clientThread;
  @Inject private ConfigManager configManager;
  @Getter @Inject private OverlayManager overlayManager;
  @Getter @Inject private PartyService partyService;
  @Getter @Inject private SlayerPluginService slayerPluginService;

  @Getter @Inject private PartnerSlayerConfig config;
  @Getter @Inject private PartnerSlayerOverlay partnerSlayerOverlay;

  @Getter private SlayerTask slayerTask;
  @Getter private String partnerName;
  private long partnerMemberId = -1L;
  private WorldPoint partnerWorldPoint;
  private WorldPoint lastOwnWorldPoint;
  private final HashSet<Integer> lastOwnPositiveHitsplatMap = new HashSet<>();

  @Override
  protected void startUp() {
    loadData();

    overlayManager.add(partnerSlayerOverlay);

    updateTask();
  }

  private void loadData() {
    partnerName = configManager.getConfiguration(PartnerSlayerConfig.CONFIG_GROUP, "partnerName");

    var taskName = configManager.getConfiguration(PartnerSlayerConfig.CONFIG_GROUP, "taskName");
    var taskInitialAmount =
        configManager.getConfiguration(PartnerSlayerConfig.CONFIG_GROUP, "taskInitialAmount");
    var taskOwnKills =
        configManager.getConfiguration(PartnerSlayerConfig.CONFIG_GROUP, "taskOwnKills");
    var taskPartnerKills =
        configManager.getConfiguration(PartnerSlayerConfig.CONFIG_GROUP, "taskPartnerKills");
    if (taskName != null
        && taskInitialAmount != null
        && taskOwnKills != null
        && taskPartnerKills != null) {
      slayerTask =
          new SlayerTask(
              taskName,
              Integer.parseInt(taskInitialAmount),
              Integer.parseInt(taskOwnKills),
              Integer.parseInt(taskPartnerKills));
    }
  }

  @Override
  protected void shutDown() {
    overlayManager.remove(partnerSlayerOverlay);

    saveData();
  }

  private void saveData() {
    configManager.setConfiguration(PartnerSlayerConfig.CONFIG_GROUP, "partnerName", partnerName);

    if (slayerTask != null) {
      configManager.setConfiguration(
          PartnerSlayerConfig.CONFIG_GROUP, "taskName", slayerTask.getTaskName());
      configManager.setConfiguration(
          PartnerSlayerConfig.CONFIG_GROUP, "taskInitialAmount", slayerTask.getInitialAmount());
      configManager.setConfiguration(
          PartnerSlayerConfig.CONFIG_GROUP, "taskOwnKills", slayerTask.getOwnKills());
      configManager.setConfiguration(
          PartnerSlayerConfig.CONFIG_GROUP, "taskPartnerKills", slayerTask.getPartnerKills());
    } else {
      configManager.unsetConfiguration(PartnerSlayerConfig.CONFIG_GROUP, "taskName");
      configManager.unsetConfiguration(PartnerSlayerConfig.CONFIG_GROUP, "taskInitialAmount");
      configManager.unsetConfiguration(PartnerSlayerConfig.CONFIG_GROUP, "taskOwnKills");
      configManager.unsetConfiguration(PartnerSlayerConfig.CONFIG_GROUP, "taskPartnerKills");
    }
  }

  private void updateTask() {
    if (client.getGameState() != GameState.LOGGED_IN) {
      return;
    }

    if (client.getVarpValue(VarPlayerID.SLAYER_TARGET) == 0) {
      // Slayer task completed, set to null
      slayerTask = null;
      return;
    }

    if (slayerTask == null
        || !slayerTask.getTaskName().equals(slayerPluginService.getTask())
        || slayerTask.getInitialAmount() != slayerPluginService.getInitialAmount()) {
      // Slayer task must have changed, reset it
      log.info(
          "Setting slayer task, task: {}, initialAmount: {}",
          slayerPluginService.getTask(),
          slayerPluginService.getInitialAmount());
      slayerTask =
          new SlayerTask(
              slayerPluginService.getTask(), slayerPluginService.getInitialAmount(), 0, 0);
    }
  }

  // Low priority so that the slayer plugin has chance to update the task first
  @Subscribe(priority = -1)
  public void onVarbitChanged(VarbitChanged varbitChanged) {
    if (varbitChanged.getVarpId() != VarPlayerID.SLAYER_TARGET
        && varbitChanged.getVarpId() != VarPlayerID.SLAYER_COUNT_ORIGINAL) {
      return;
    }

    clientThread.invokeLater(this::updateTask);
  }

  @Subscribe
  public void onChatMessage(ChatMessage chatMessage) {
    if (chatMessage.getType() != ChatMessageType.GAMEMESSAGE) {
      return;
    }

    for (Pattern partnerTaskPattern : PARTNER_TASK_PATTERNS) {
      var matcher = partnerTaskPattern.matcher(chatMessage.getMessage());
      if (matcher.find()) {
        setPartnerName(matcher.group(1));
        return;
      }
    }
  }

  private void shareLocation() {
    if (client.getGameState() != GameState.LOGGED_IN
        || client.getLocalPlayer() == null
        || partnerMemberId == -1
        || !partyService.isInParty()
        || slayerTask == null) {
      return;
    }

    WorldPoint location = client.getLocalPlayer().getWorldLocation();
    if (location.equals(lastOwnWorldPoint)) {
      return;
    }

    lastOwnWorldPoint = location;

    final LocationUpdate locationUpdate = new LocationUpdate(location);
    partyService.send(locationUpdate);
  }

  @Subscribe
  public void onGameTick(GameTick gameTick) {
    shareLocation();
  }

  private void joinParty() {
    if (partnerMemberId != -1) {
      return;
    }

    if (partyService.isInParty()) {
      if (partyService.getMemberByDisplayName(partnerName) != null) {
        return;
      }

      client.addChatMessage(
          ChatMessageType.GAMEMESSAGE,
          "",
          "You are in a party that does not contain your slayer partner, the Partner Slayer plugin"
              + " won't work unless you are in a party with your partner.",
          null);
      return;
    }

    var partyPassword =
        "PS/"
            + Stream.of(partnerName, client.getLocalPlayer().getName())
                .filter(Objects::nonNull)
                .sorted(String::compareTo)
                .collect(Collectors.joining("/"));

    partyService.changeParty(partyPassword);
    client.addChatMessage(
        ChatMessageType.GAMEMESSAGE,
        "",
        "Partner Slayer has automatically created a party for you. Party password: "
            + partyPassword,
        null);
  }

  private void setPartnerName(String name) {
    partnerName = name;

    joinParty();
  }

  @Subscribe
  public void onNpcSpawned(NpcSpawned npcSpawned) {
    lastOwnPositiveHitsplatMap.remove(npcSpawned.getNpc().getId());
  }

  @Subscribe
  public void onNpcDespawned(NpcDespawned npcDespawned) {
    lastOwnPositiveHitsplatMap.remove(npcDespawned.getNpc().getId());
  }

  @Subscribe
  public void onHitsplatApplied(HitsplatApplied hitsplatApplied) {
    if (!(hitsplatApplied.getActor() instanceof NPC)) {
      return;
    }

    var npcId = ((NPC) hitsplatApplied.getActor()).getId();
    var hitsplat = hitsplatApplied.getHitsplat();
    if (hitsplat.getAmount() > 0) {
      if (hitsplat.isMine()) {
        lastOwnPositiveHitsplatMap.add(npcId);
      } else {
        lastOwnPositiveHitsplatMap.remove(npcId);
      }
    }
  }

  @Subscribe
  public void onActorDeath(ActorDeath actorDeath) {
    if (!(actorDeath.getActor() instanceof NPC)
        || !slayerPluginService.getTargets().contains(actorDeath.getActor())) {
      return;
    }

    if (weKilledIt((NPC) actorDeath.getActor())) {
      incrementOwnKills();
      slayerTask.setOwnKills(slayerTask.getOwnKills() + 1);
    }
  }

  private void incrementOwnKills() {
    if (slayerTask == null) {
      return;
    }

    slayerTask.setOwnKills(slayerTask.getOwnKills() + 1);

    if (partyService.isInParty()) {
      partyService.send(new SlayerTaskKillsUpdate(slayerTask.getOwnKills()));
    }
  }

  private boolean weKilledIt(NPC npc) {
    return lastOwnPositiveHitsplatMap.contains(npc.getId());
  }

  @Subscribe
  public void onUserJoin(UserJoin userJoin) {
    var partyMember = partyService.getMemberById(userJoin.getMemberId());
    if (partyMember.getDisplayName().equals(partnerName)) {
      log.info("Partner {} has joined the party.", partnerName);

      partnerMemberId = userJoin.getMemberId();
    }
  }

  @Subscribe
  public void onUserPart(UserPart userPart) {
    var partyMember = partyService.getMemberById(userPart.getMemberId());
    if (partyMember.getDisplayName().equals(partnerName)) {
      log.info("Partner {} has left the party.", partnerName);

      partnerMemberId = -1;
      partnerWorldPoint = null;
    }
  }

  @Subscribe
  public void onLocationUpdate(LocationUpdate locationUpdate) {
    if (locationUpdate.getMemberId() != partnerMemberId) {
      return;
    }

    partnerWorldPoint = locationUpdate.getWorldPoint();
  }

  @Subscribe
  public void onSlayerTaskKillsUpdate(SlayerTaskKillsUpdate slayerTaskKillsUpdate) {
    if (slayerTaskKillsUpdate.getMemberId() != partnerMemberId || slayerTask == null) {
      return;
    }

    slayerTask.setPartnerKills(slayerTaskKillsUpdate.getKills());
  }

  public int getPartnerDistance() {
    if (client.getLocalPlayer() == null || partnerWorldPoint == null) {
      return -1;
    }

    return client.getLocalPlayer().getWorldLocation().distanceTo(partnerWorldPoint);
  }

  @Provides
  PartnerSlayerConfig provideConfig(ConfigManager configManager) {
    return configManager.getConfig(PartnerSlayerConfig.class);
  }
}
