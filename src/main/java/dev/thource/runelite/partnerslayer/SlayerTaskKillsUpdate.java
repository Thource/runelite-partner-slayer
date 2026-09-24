package dev.thource.runelite.partnerslayer;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import net.runelite.client.party.messages.PartyMemberMessage;

@Data
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class SlayerTaskKillsUpdate extends PartyMemberMessage {
  @SerializedName("k")
  private final int kills;
}
