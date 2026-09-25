package dev.thource.runelite.partnerslayer.party;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import net.runelite.client.party.messages.PartyMemberMessage;

@Data
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class PartnerSlayerNameUpdate extends PartyMemberMessage {
  @SerializedName("n")
  private final String name;
}
