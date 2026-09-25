package dev.thource.runelite.partnerslayer.party;

import lombok.ToString;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.party.messages.PartyMemberMessage;

@ToString(onlyExplicitlyIncluded = true)
public class PartnerSlayerLocationUpdate extends PartyMemberMessage {
  private final int c;

  public PartnerSlayerLocationUpdate(WorldPoint worldPoint)
  {
    c = (worldPoint.getPlane() << 28) | (worldPoint.getX() << 14) | (worldPoint.getY());
  }

  @ToString.Include
  public WorldPoint getWorldPoint()
  {
    return new WorldPoint(
        (c >> 14) & 0x3fff,
        c & 0x3fff,
        (c >> 28) & 3
    );
  }
}
