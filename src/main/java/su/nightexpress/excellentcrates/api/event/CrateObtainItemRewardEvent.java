package su.nightexpress.excellentcrates.api.event;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import su.nightexpress.excellentcrates.crate.impl.Reward;

public class CrateObtainItemRewardEvent extends CrateEvent {

    private final Reward reward;
    private ItemStack item;

    public CrateObtainItemRewardEvent(@NotNull Reward reward, @NotNull ItemStack item, @NotNull Player player) {
        super(reward.getCrate(), player);

        this.reward = reward;
        this.item = item;
    }

    public void setItem(@NotNull ItemStack item) {
        this.item = item;
    }

    public @NotNull ItemStack getItem() {
        return item;
    }

    @NotNull
    public Reward getReward() {
        return reward;
    }
}
