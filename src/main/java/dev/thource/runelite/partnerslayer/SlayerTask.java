package dev.thource.runelite.partnerslayer;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SlayerTask {
  private String taskName;
  private int initialAmount;
  private int ownKills;
  private int ownXP;
  private int partnerKills;
  private int partnerXP;
}
