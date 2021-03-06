package com.hm.petmaster.listener;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitRunnable;

import com.gmail.filoghost.holographicdisplays.api.Hologram;
import com.gmail.filoghost.holographicdisplays.api.HologramsAPI;
import com.hm.mcshared.event.PlayerChangeAnimalOwnershipEvent;
import com.hm.mcshared.particle.FancyMessageSender;
import com.hm.mcshared.particle.ReflectionUtils.PackageType;
import com.hm.petmaster.PetMaster;

import net.milkbowl.vault.economy.Economy;

/**
 * Class used to display holograms, change the owner of a pet or free a pet.
 * 
 * @author Pyves
 *
 */
public class PlayerInteractListener implements Listener {

	// Vertical offsets of the holograms for each mob type.
	private static final double DOG_OFFSET = 1.5;
	private static final double CAT_OFFSET = 1.42;
	private static final double HORSE_OFFSET = 2.32;
	private static final double LLAMA_OFFSET = 2.42;
	private static final double PARROT_OFFSET = 1.15;

	private final PetMaster plugin;
	private final int version;
	private Economy economy;

	// Configuration parameters.
	private boolean chatMessage;
	private boolean hologramMessage;
	private boolean actionBarMessage;
	private boolean displayDog;
	private boolean displayCat;
	private boolean displayHorse;
	private boolean displayLlama;
	private boolean displayParrot;
	private boolean displayToOwner;
	private int hologramDuration;
	private int changeOwnerPrice;
	private int freePetPrice;
	private boolean showHealth;

	public PlayerInteractListener(PetMaster petMaster) {
		this.plugin = petMaster;
		version = Integer.parseInt(PackageType.getServerVersion().split("_")[1]);
		// Try to retrieve an Economy instance from Vault.
		if (Bukkit.getServer().getPluginManager().getPlugin("Vault") != null) {
			RegisteredServiceProvider<Economy> rsp = Bukkit.getServer().getServicesManager()
					.getRegistration(Economy.class);
			if (rsp != null) {
				economy = rsp.getProvider();
			}
		}
	}

	public void extractParameters() {
		displayDog = plugin.getPluginConfig().getBoolean("displayDog", true);
		displayCat = plugin.getPluginConfig().getBoolean("displayCat", true);
		displayHorse = plugin.getPluginConfig().getBoolean("displayHorse", true);
		displayLlama = plugin.getPluginConfig().getBoolean("displayLlama", true);
		displayParrot = plugin.getPluginConfig().getBoolean("displayParrot", true);
		displayToOwner = plugin.getPluginConfig().getBoolean("displayToOwner", false);
		hologramDuration = plugin.getPluginConfig().getInt("hologramDuration", 50);
		changeOwnerPrice = plugin.getPluginConfig().getInt("changeOwnerPrice", 0);
		freePetPrice = plugin.getPluginConfig().getInt("freePetPrice", 0);
		chatMessage = plugin.getPluginConfig().getBoolean("chatMessage", true);
		hologramMessage = plugin.getPluginConfig().getBoolean("hologramMessage", false);
		actionBarMessage = plugin.getPluginConfig().getBoolean("actionBarMessage", true);
		showHealth = plugin.getPluginConfig().getBoolean("showHealth", true);

		boolean holographicDisplaysAvailable = Bukkit.getPluginManager().isPluginEnabled("HolographicDisplays");
		// Checking whether user configured plugin to display hologram but HolographicsDisplays not available.
		if (hologramMessage && !holographicDisplaysAvailable) {
			plugin.setSuccessfulLoad(false);
			hologramMessage = false;
			actionBarMessage = true;
			plugin.getLogger().warning(
					"HolographicDisplays was not found; disabling usage of holograms and enabling action bar messages.");
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlayerInteractEntityEvent(PlayerInteractEntityEvent event) {
		// On Minecraft versions from 1.9 onwards, this event is fired twice, one for each hand. Need additional check.
		if (version >= 9 && event.getHand() != EquipmentSlot.HAND) {
			return;
		}

		if (!(event.getRightClicked() instanceof Tameable) || ((Tameable) event.getRightClicked()).getOwner() == null
				|| !plugin.getEnableDisableCommand().isEnabled()) {
			return;
		}

		AnimalTamer currentOwner = ((Tameable) event.getRightClicked()).getOwner();
		// Has the player clicked on one of his own pets?
		boolean isOwner = event.getPlayer().getName().equals(currentOwner.getName());
		// Retrieve new owner from the map and delete corresponding entry.
		Player newOwner = plugin.getSetOwnerCommand().collectPendingSetOwnershipRequest(event.getPlayer());
		// Has the player requested to free one of his pets?
		boolean freePet = plugin.getFreeCommand().collectPendingFreeRequest(event.getPlayer());

		// Cannot change ownership or free pet if not owner and no bypass permission.
		if ((newOwner != null || freePet) && !isOwner && !event.getPlayer().hasPermission("petmaster.admin")) {
			event.getPlayer().sendMessage(plugin.getChatHeader() + plugin.getPluginLang()
					.getString("not-owner", "You do not own this pet!").replace("PLAYER", event.getPlayer().getName()));
			return;
		}

		if (newOwner != null) {
			changeOwner(event, currentOwner, newOwner);
		} else if (freePet) {
			freePet(event, currentOwner);
		} else if ((displayToOwner || !isOwner) && event.getPlayer().hasPermission("petmaster.showowner")) {
			displayHologramAndMessage(event, currentOwner);
		}
	}

	/**
	 * Change the owner of a pet.
	 * 
	 * @param event
	 * @param oldOwner
	 * @param newOwner
	 */
	private void changeOwner(PlayerInteractEntityEvent event, AnimalTamer oldOwner, Player newOwner) {
		if (chargePrice(event.getPlayer(), changeOwnerPrice)) {
			Tameable tameableAnimal = (Tameable) event.getRightClicked();
			// Change owner.
			tameableAnimal.setOwner(newOwner);
			event.getPlayer().sendMessage(plugin.getChatHeader()
					+ plugin.getPluginLang().getString("owner-changed", "This pet was given to a new owner!"));
			newOwner.sendMessage(plugin.getChatHeader()
					+ plugin.getPluginLang().getString("new-owner", "Player PLAYER gave you ownership of a pet!")
							.replace("PLAYER", event.getPlayer().getName()));

			// Create new event to allow other plugins to be aware of the ownership change.
			PlayerChangeAnimalOwnershipEvent playerChangeAnimalOwnershipEvent = new PlayerChangeAnimalOwnershipEvent(
					oldOwner, newOwner, tameableAnimal);
			Bukkit.getServer().getPluginManager().callEvent(playerChangeAnimalOwnershipEvent);
		}
	}

	/**
	 * Frees a pet; it will no longer be tamed.
	 * 
	 * @param event
	 * @param oldOwner
	 */
	private void freePet(PlayerInteractEntityEvent event, AnimalTamer oldOwner) {
		if (chargePrice(event.getPlayer(), freePetPrice)) {
			Tameable tameableAnimal = (Tameable) event.getRightClicked();
			// Free pet.
			tameableAnimal.setTamed(false);
			// Make freed pet stand up.
			if (version >= 12 && tameableAnimal instanceof Sittable) {
				((Sittable) tameableAnimal).setSitting(false);
			} else if (tameableAnimal instanceof Wolf) {
				((Wolf) tameableAnimal).setSitting(false);
			} else if (tameableAnimal instanceof Ocelot) {
				((Ocelot) tameableAnimal).setSitting(false);
			}

			event.getPlayer().sendMessage(plugin.getChatHeader()
					+ plugin.getPluginLang().getString("pet-freed", "Say goodbye: this pet returned to the wild!"));

			// Create new event to allow other plugins to be aware of the freeing.
			PlayerChangeAnimalOwnershipEvent playerChangeAnimalOwnershipEvent = new PlayerChangeAnimalOwnershipEvent(
					oldOwner, null, tameableAnimal);
			Bukkit.getServer().getPluginManager().callEvent(playerChangeAnimalOwnershipEvent);
		}
	}

	/**
	 * Displays a hologram, and automatically delete it after a given delay.
	 * 
	 * @param event
	 * @param owner
	 */
	@SuppressWarnings("deprecation")
	private void displayHologramAndMessage(PlayerInteractEntityEvent event, AnimalTamer owner) {
		if (hologramMessage) {
			Entity clickedAnimal = event.getRightClicked();

			double offset = HORSE_OFFSET;
			if (clickedAnimal instanceof Ocelot) {
				if (!displayCat || !event.getPlayer().hasPermission("petmaster.showowner.cat")) {
					return;
				}
				offset = CAT_OFFSET;
			} else if (clickedAnimal instanceof Wolf) {
				if (!displayDog || !event.getPlayer().hasPermission("petmaster.showowner.dog")) {
					return;
				}
				offset = DOG_OFFSET;
			} else if (version >= 11 && clickedAnimal instanceof Llama) {
				if (!displayLlama || !event.getPlayer().hasPermission("petmaster.showowner.llama")) {
					return;
				}
				offset = LLAMA_OFFSET;
			} else if (version >= 12 && clickedAnimal instanceof Parrot) {
				if (!displayParrot || !event.getPlayer().hasPermission("petmaster.showowner.parrot")) {
					return;
				}
				offset = PARROT_OFFSET;
			} else if (!displayHorse || !event.getPlayer().hasPermission("petmaster.showowner.horse")) {
				return;
			}

			Location eventLocation = clickedAnimal.getLocation();
			// Create location with offset.
			Location hologramLocation = new Location(eventLocation.getWorld(), eventLocation.getX(),
					eventLocation.getY() + offset, eventLocation.getZ());

			final Hologram hologram = HologramsAPI.createHologram(plugin, hologramLocation);
			hologram.appendTextLine(
					ChatColor.GRAY + plugin.getPluginLang().getString("petmaster-hologram", "Pet owned by ")
							+ ChatColor.GOLD + owner.getName());

			// Runnable to delete hologram.
			new BukkitRunnable() {

				@Override
				public void run() {

					hologram.delete();
				}
			}.runTaskLater(plugin, hologramDuration);
		}

		String healthInfo = "";
		if (showHealth) {
			Animals damageable = (Animals) event.getRightClicked();
			String currentHealth = String.format("%.1f", damageable.getHealth());
			String maxHealth = version < 9 ? String.format("%.1f", damageable.getMaxHealth())
					: String.format("%.1f", damageable.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue());
			healthInfo = ChatColor.GRAY + ". " + plugin.getPluginLang().getString("petmaster-health", "Health: ")
					+ ChatColor.GOLD + currentHealth + "/" + maxHealth;
		}

		if (chatMessage) {
			event.getPlayer().sendMessage(
					plugin.getChatHeader() + plugin.getPluginLang().getString("petmaster-chat", "Pet owned by ")
							+ ChatColor.GOLD + owner.getName() + healthInfo);
		}

		if (actionBarMessage) {
			try {
				FancyMessageSender.sendActionBarMessage(event.getPlayer(), "&o" + ChatColor.GRAY
						+ plugin.getPluginLang().getString("petmaster-action-bar", "Pet owned by ") + ChatColor.GOLD
						+ owner.getName() + healthInfo);
			} catch (Exception e) {
				plugin.getLogger().warning("Errors while trying to display action bar message for pet ownership.");
			}
		}
	}

	/**
	 * Charges a player if he has enough money and displays relevant messages.
	 * 
	 * @param player
	 * @param price
	 * @return true if money should be withdrawn from the player, false otherwise
	 */
	private boolean chargePrice(Player player, int price) {
		// Charge player for changing ownership.
		if (price > 0 && !player.hasPermission("petmaster.admin") && economy != null) {
			// If server has set different currency names depending on amount, adapt message accordingly.
			String priceWithCurrency = price + " "
					+ (price > 1 ? economy.currencyNamePlural() : economy.currencyNameSingular());
			if (economy.getBalance(player) < price) {
				player.sendMessage(plugin.getChatHeader() + ChatColor.translateAlternateColorCodes('&',
						plugin.getPluginLang()
								.getString("not-enough-money", "You do not have the required amount: AMOUNT!")
								.replace("AMOUNT", priceWithCurrency)));
				return false;
			}
			economy.withdrawPlayer(player, price);
			player.sendMessage(plugin.getChatHeader() + ChatColor.translateAlternateColorCodes('&',
					plugin.getPluginLang().getString("change-owner-price", "You payed: AMOUNT!").replace("AMOUNT",
							priceWithCurrency)));
		}
		return true;
	}
}
