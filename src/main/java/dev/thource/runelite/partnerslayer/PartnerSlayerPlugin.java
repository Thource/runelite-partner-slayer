package dev.thource.runelite.partnerslayer;

import com.google.inject.Provides;
import dev.thource.runelite.partnerslayer.party.PartnerSlayerKillsUpdate;
import dev.thource.runelite.partnerslayer.party.PartnerSlayerLocationUpdate;
import dev.thource.runelite.partnerslayer.party.PartnerSlayerNameUpdate;
import dev.thource.runelite.partnerslayer.party.PartnerSlayerXPUpdate;
import java.util.Arrays;
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
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigSync;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.WSClient;
import net.runelite.client.party.events.UserJoin;
import net.runelite.client.party.events.UserPart;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.slayer.SlayerPlugin;
import net.runelite.client.plugins.slayer.SlayerPluginService;
import net.runelite.client.ui.overlay.OverlayManager;

/*
 * TODO:
 *  - Config options
 *  - Read slayer partner from "partner" option on slayer gem
 *  - Add chat message after task completed: "Kills: {}, XP gained: {} - Partner kills: {}, Partner XP gained: {}"
 *  - Only show the overlay after receiving a new task or when near slayer task monsters (5 min timeout)
 */

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
  @Inject private WSClient wsClient;

  @Getter @Inject private PartnerSlayerConfig config;
  @Getter @Inject private PartnerSlayerOverlay partnerSlayerOverlay;

  @Getter private SlayerTask slayerTask;
  @Getter private String partnerName;
  @Getter private long partnerMemberId = -1L;
  private WorldPoint partnerWorldPoint;
  private WorldPoint lastOwnWorldPoint;
  private final HashSet<Integer> lastOwnPositiveHitsplatMap = new HashSet<>();
  private String rsProfileKey;
  private int slayerXp = -1;

  @Override
  protected void startUp() {
    wsClient.registerMessage(PartnerSlayerNameUpdate.class);
    wsClient.registerMessage(PartnerSlayerLocationUpdate.class);
    wsClient.registerMessage(PartnerSlayerKillsUpdate.class);

    overlayManager.add(partnerSlayerOverlay);

    if (configManager.getRSProfileKey() != null) {
      load(configManager.getRSProfileKey());
    } else {
      log.info("profile key null");
    }
  }

  @Override
  protected void shutDown() {
    overlayManager.remove(partnerSlayerOverlay);

    save();

    wsClient.unregisterMessage(PartnerSlayerNameUpdate.class);
    wsClient.unregisterMessage(PartnerSlayerLocationUpdate.class);
    wsClient.unregisterMessage(PartnerSlayerKillsUpdate.class);
  }

  private void save() {
    if (rsProfileKey == null) {
      return;
    }

    configManager.setConfiguration(
        PartnerSlayerConfig.CONFIG_GROUP, rsProfileKey, "partnerName", partnerName);

    if (slayerTask != null) {
      configManager.setConfiguration(
          PartnerSlayerConfig.CONFIG_GROUP, rsProfileKey, "taskName", slayerTask.getTaskName());
      configManager.setConfiguration(
          PartnerSlayerConfig.CONFIG_GROUP,
          rsProfileKey,
          "taskInitialAmount",
          slayerTask.getInitialAmount());
      configManager.setConfiguration(
          PartnerSlayerConfig.CONFIG_GROUP, rsProfileKey, "taskOwnKills", slayerTask.getOwnKills());
      configManager.setConfiguration(
          PartnerSlayerConfig.CONFIG_GROUP, rsProfileKey, "taskOwnXP", slayerTask.getOwnXP());
      configManager.setConfiguration(
          PartnerSlayerConfig.CONFIG_GROUP,
          rsProfileKey,
          "taskPartnerKills",
          slayerTask.getPartnerKills());
      configManager.setConfiguration(
          PartnerSlayerConfig.CONFIG_GROUP,
          rsProfileKey,
          "taskPartnerXP",
          slayerTask.getPartnerXP());
    } else {
      configManager.unsetConfiguration(PartnerSlayerConfig.CONFIG_GROUP, rsProfileKey, "taskName");
      configManager.unsetConfiguration(
          PartnerSlayerConfig.CONFIG_GROUP, rsProfileKey, "taskInitialAmount");
      configManager.unsetConfiguration(
          PartnerSlayerConfig.CONFIG_GROUP, rsProfileKey, "taskOwnKills");
      configManager.unsetConfiguration(PartnerSlayerConfig.CONFIG_GROUP, rsProfileKey, "taskOwnXP");
      configManager.unsetConfiguration(
          PartnerSlayerConfig.CONFIG_GROUP, rsProfileKey, "taskPartnerKills");
      configManager.unsetConfiguration(
          PartnerSlayerConfig.CONFIG_GROUP, rsProfileKey, "taskPartnerXP");
    }
  }

  private void load(String rsProfileKey) {
    this.rsProfileKey = rsProfileKey;

    partnerName =
        configManager.getConfiguration(
            PartnerSlayerConfig.CONFIG_GROUP, rsProfileKey, "partnerName");

    var taskName =
        configManager.getConfiguration(PartnerSlayerConfig.CONFIG_GROUP, rsProfileKey, "taskName");
    var taskInitialAmount =
        configManager.getConfiguration(
            PartnerSlayerConfig.CONFIG_GROUP, rsProfileKey, "taskInitialAmount");
    var taskOwnKills =
        configManager.getConfiguration(
            PartnerSlayerConfig.CONFIG_GROUP, rsProfileKey, "taskOwnKills");
    var taskOwnXP =
        configManager.getConfiguration(PartnerSlayerConfig.CONFIG_GROUP, rsProfileKey, "taskOwnXP");
    var taskPartnerKills =
        configManager.getConfiguration(
            PartnerSlayerConfig.CONFIG_GROUP, rsProfileKey, "taskPartnerKills");
    var taskPartnerXP =
        configManager.getConfiguration(
            PartnerSlayerConfig.CONFIG_GROUP, rsProfileKey, "taskPartnerXP");
    if (taskName != null
        && taskInitialAmount != null
        && taskOwnKills != null
        && taskOwnXP != null
        && taskPartnerKills != null
        && taskPartnerXP != null) {
      slayerTask =
          new SlayerTask(
              taskName,
              Integer.parseInt(taskInitialAmount),
              Integer.parseInt(taskOwnKills),
              Integer.parseInt(taskOwnXP),
              Integer.parseInt(taskPartnerKills),
              Integer.parseInt(taskPartnerXP));
    }
  }

  @Subscribe
  void onGameStateChanged(GameStateChanged gameStateChanged) {
    if (gameStateChanged.getGameState() == GameState.LOGGING_IN) {
      slayerXp = -1;
    }
  }

  @Subscribe
  void onStatChanged(StatChanged statChanged) {
    if (statChanged.getSkill() != Skill.SLAYER) {
      return;
    }

    // if slayerXp is -1, this is the server sending us our total XP, so don't count it
    if (slayerXp != -1) {
      var xpDiff = statChanged.getXp() - slayerXp;

      if (xpDiff > 0 && slayerTask != null) {
        // TODO: also check not timed out
        slayerTask.setOwnXP(slayerTask.getOwnXP() + xpDiff);
        shareXp();
      }
    }

    slayerXp = statChanged.getXp();
  }

  @Subscribe
  void onPartnerSlayerXPUpdate(PartnerSlayerXPUpdate partnerSlayerXPUpdate) {
    if (slayerTask == null) {
      return;
    }

    slayerTask.setPartnerXP(partnerSlayerXPUpdate.getXp());
  }

  @Subscribe
  void onRuneScapeProfileChanged(RuneScapeProfileChanged e) {
    save();
    load(configManager.getRSProfileKey());
  }

  @Subscribe
  public void onConfigSync(ConfigSync configSync) {
    save();
  }

  @Subscribe
  public void onClientShutdown(ClientShutdown clientShutdown) {
    save();
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
        || slayerTask.getInitialAmount() != slayerPluginService.getInitialAmount()
        || slayerTask.getTaskName() == null
        || !slayerTask.getTaskName().equals(slayerPluginService.getTask())) {
      if (slayerPluginService.getTask() != null) {
        // Slayer task must have changed, reset it
        log.info(
            "Setting slayer task, task: {}, initialAmount: {}",
            slayerPluginService.getTask(),
            slayerPluginService.getInitialAmount());
        slayerTask =
            new SlayerTask(
                slayerPluginService.getTask(), slayerPluginService.getInitialAmount(), 0, 0, 0, 0);
      }
    }
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

  @Subscribe
  public void onCommandExecuted(CommandExecuted commandExecuted) {
    var command = commandExecuted.getCommand();
    var args = commandExecuted.getArguments();

    if (command.equals("setpartner")) {
      if (args.length == 0) {
        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Setting slayer partner failed, no name specified.", null);
        return;
      }

      var partnerName =
          Arrays.stream(args).filter(Objects::nonNull).collect(Collectors.joining(" "));
      setPartnerName(partnerName);
    }
  }

  private void shareName() {
    if (client.getGameState() != GameState.LOGGED_IN
        || client.getLocalPlayer() == null
        || !partyService.isInParty()) {
      return;
    }

    partyService.send(new PartnerSlayerNameUpdate(client.getLocalPlayer().getName()));
  }

  private void shareLocation() {
    if (client.getGameState() != GameState.LOGGED_IN
        || client.getLocalPlayer() == null
        || partnerMemberId == -1L
        || !partyService.isInParty()
        || slayerTask == null) {
      return;
    }

    WorldPoint location = client.getLocalPlayer().getWorldLocation();
    if (location.equals(lastOwnWorldPoint)) {
      return;
    }

    lastOwnWorldPoint = location;
    partyService.send(new PartnerSlayerLocationUpdate(location));
  }

  private void shareXp() {
    if (slayerTask == null || !partyService.isInParty()) {
      return;
    }

    partyService.send(new PartnerSlayerXPUpdate(slayerTask.getOwnXP()));
  }

  @Subscribe
  public void onGameTick(GameTick gameTick) {
    shareLocation();
    updateTask();
  }

  private void joinParty() {
    if (partnerMemberId != -1L) {
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
    }
  }

  private void incrementOwnKills() {
    if (slayerTask == null) {
      return;
    }

    slayerTask.setOwnKills(slayerTask.getOwnKills() + 1);

    if (partyService.isInParty()) {
      partyService.send(new PartnerSlayerKillsUpdate(slayerTask.getOwnKills()));
    }
  }

  private boolean weKilledIt(NPC npc) {
    return lastOwnPositiveHitsplatMap.contains(npc.getId());
  }

  @Subscribe
  public void onPartyChanged(PartyChanged partyChanged) {
    log.info("Party changed.");
    shareName();
    shareLocation();
  }

  @Subscribe
  public void onUserJoin(UserJoin userJoin) {
    log.info("User {} has joined the party.", userJoin.getMemberId());
    shareName();
    shareLocation();
  }

  @Subscribe
  public void onUserPart(UserPart userPart) {
    if (userPart.getMemberId() == partnerMemberId) {
      log.info("Partner {} has left the party.", partnerName);

      partnerMemberId = -1L;
      partnerWorldPoint = null;
    }
  }

  @Subscribe
  public void onPartnerSlayerNameUpdate(PartnerSlayerNameUpdate partnerSlayerNameUpdate) {
    var memberId = partnerSlayerNameUpdate.getMemberId();
    var name = partnerSlayerNameUpdate.getName();

    log.info("Received name update from {}: {}", name, memberId);
    if (partnerMemberId != -1L || name == null || !name.equals(partnerName)) {
      return;
    }

    partnerMemberId = memberId;
  }

  @Subscribe
  public void onPartnerSlayerLocationUpdate(
      PartnerSlayerLocationUpdate partnerSlayerLocationUpdate) {
    if (partnerSlayerLocationUpdate.getMemberId() != partnerMemberId) {
      return;
    }

    partnerWorldPoint = partnerSlayerLocationUpdate.getWorldPoint();
  }

  @Subscribe
  public void onPartnerSlayerKillsUpdate(PartnerSlayerKillsUpdate partnerSlayerKillsUpdate) {
    if (partnerSlayerKillsUpdate.getMemberId() != partnerMemberId || slayerTask == null) {
      return;
    }

    slayerTask.setPartnerKills(partnerSlayerKillsUpdate.getKills());
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
