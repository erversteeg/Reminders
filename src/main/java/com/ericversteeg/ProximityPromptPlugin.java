package com.ericversteeg;

import com.ericversteeg.model.config.Location;
import com.ericversteeg.model.config.Sound;
import com.ericversteeg.model.config.TextSize;
import com.ericversteeg.model.config.TimeUnit;
import com.ericversteeg.model.Prompt;
import com.ericversteeg.view.RSAnchorType;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import javax.inject.Provider;
import java.awt.*;
import java.time.*;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@PluginDescriptor(
	name = "Proximity Prompt",
	description = "Create prompts that appear only sometimes."
)

public class ProximityPromptPlugin extends Plugin {

	@Inject
	private ProximityPromptOverlay overlay;
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private Client client;
	@Inject
	private ProximityPromptConfig config;
	@Inject
	private ConfigManager configManager;
	@Inject
	private Gson gson;
	@Inject
	private ItemManager itemManager;
	@Inject
	private Notifier notifier;
	@Inject
	private Provider<ClientThread> clientThreadProvider;

	List<Prompt> prompts = new ArrayList<>();
	List<Prompt> activePrompts = new ArrayList<>();
	List<Prompt> inactivePrompts = new ArrayList<>();

	LocalDateTime dateTime = LocalDateTime.now(ZoneId.systemDefault());

	int regionId = 0;
	int lastRegionId = 0;

	List<NPC> npcs = new ArrayList<>();

	List<ChatMessage> chatMessages = new ArrayList<>();

	List<ItemComposition> items = new ArrayList<>();

	WorldPoint worldPos = new WorldPoint(0, 0, 0);

	@Override
	protected void startUp() throws Exception
	{
		overlayManager.add(overlay);

		prompts = getAllPrompts();
		activePrompts = getActivePrompts(prompts);
	}

	@Override
	protected void shutDown() throws Exception
	{
		overlayManager.remove(overlay);
	}

	@Provides
	ProximityPromptConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ProximityPromptConfig.class);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged config)
	{
		if (config.getGroup().equals(ProximityPromptConfig.GROUP))
		{
			prompts = getAllPrompts();
			activePrompts = getActivePrompts(prompts);

			if (config.getKey().matches("^.*?Sound$"))
			{
				clientThreadProvider.get().invoke(() ->
						client.playSoundEffect(
								Sound.valueOf(config.getNewValue()).getId(),
								SoundEffectVolume.HIGH
						)
				);
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		dateTime = LocalDateTime.now(ZoneId.systemDefault());

		activePrompts = getActivePrompts(prompts);

		items.clear();

		regionId = WorldPoint.fromLocalInstance(
				client,
				client.getLocalPlayer().getLocalLocation()
		).getRegionID();

		lastRegionId = regionId;

		worldPos = client.getLocalPlayer().getWorldLocation();

		final ItemContainer itemContainer = client.getItemContainer(InventoryID.INVENTORY);

		if (itemContainer == null)
		{
			return;
		}

		final Item[] inventoryItems = itemContainer.getItems();
		for (Item item : inventoryItems)
		{
			ItemComposition itemComposition = itemManager.getItemComposition(item.getId());

			if (!itemsContainsId(itemComposition.getId()))
			{
				items.add(itemComposition);
			}
		}

		final List<ItemComposition> equipmentItems = getEquipment();
		for (ItemComposition itemComposition: equipmentItems)
		{
			if (!itemsContainsId(itemComposition.getId()))
			{
				items.add(itemComposition);
			}
		}
	}

	private List<ItemComposition> getEquipment()
	{
		ItemContainer itemContainer = client.getItemContainer(InventoryID.EQUIPMENT);

		if (itemContainer == null)
		{
			return new ArrayList<>();
		}

		Item ring = itemContainer.getItem(EquipmentInventorySlot.RING.getSlotIdx());
		Item ammo = itemContainer.getItem(EquipmentInventorySlot.AMMO.getSlotIdx());

		Player player = client.getLocalPlayer();

		int [] ids = player.getPlayerComposition().getEquipmentIds();

		List<Integer> eIds = new ArrayList<>();

		for (int id: ids)
		{
			if (id < 512)
			{
				continue;
			}

			eIds.add(id - 512);
		}

		if (ring != null)
		{
			eIds.add(ring.getId());
		}

		if (ammo != null)
		{
			eIds.add(ammo.getId());
		}

		return eIds.stream().map(itemManager::getItemComposition)
				.collect(Collectors.toList());
	}

	@Subscribe(priority = -1)
	private void onNpcSpawned(NpcSpawned npcSpawned)
	{
		NPC npc = npcSpawned.getNpc();
		npcs.add(npc);
	}

	@Subscribe(priority = -1)
	public void onNpcDespawned(NpcDespawned npcDespawned)
	{
		NPC npc = npcDespawned.getNpc();
		npcs.remove(npc);
	}

	@Subscribe
	public void onChatMessage(ChatMessage chatMessage)
	{
		chatMessages.add(chatMessage);
	}

	private boolean npcsContainsName(String name)
	{
		for (NPC npc : npcs) {
			if (name.equals(npc.getName()))
			{
				return true;
			}
		}
		return false;
	}

	private boolean itemsContainsId(int id)
	{
		for (ItemComposition item : items)
		{
			if (id == item.getId())
			{
				return true;
			}
		}
		return false;
	}

	private List<Prompt> getAllPrompts()
	{
		long start = Instant.now().toEpochMilli();

		List<Prompt> prompts = new ArrayList<>();

		Pattern colorPattern = Pattern.compile("\\^\\w");

		for (int i = 1; i <= 100; i++)
		{
			Prompt prompt = new Prompt();

			prompt.id = i;
			prompt.enable = isEnable(i);
			prompt.forceShow = isForceShow(i);
			prompt.text = getText(i);
			prompt.colur = getColor(i);
			prompt.duration = getDuration(i);
			prompt.cooldown = getCooldown(i);
			prompt.timeUnit = getTimeUnit(i).getType();
			prompt.notify = isNotify(i);
			prompt.times = getTimes(i);
			prompt.daysOfWeek = getDaysOfWeek(i);
			prompt.dates = getDates(i);
			prompt.coordinates = getCoordinates(i);
			prompt.radius = getRadius(i);
			prompt.geoFences = getGeofences(i);
			prompt.regionIds = getRegionIds(i);
			prompt.npcIds = getNpcIds(i);
			prompt.itemIds = getItemIds(i);
			prompt.chatPatterns = getChatPatterns(i);

			prompts.add(prompt);
		}

		try {
			JsonArray jsonArray = gson.fromJson(config.customReminders(), JsonArray.class);

			int i = 1;
			for (JsonElement jsonElement: jsonArray)
			{
				JsonObject jsonObject = jsonElement.getAsJsonObject();

				Prompt prompt = new Prompt();

				prompt.id = 100 + i;

				if (jsonObject.has("enable"))
				{
					prompt.enable = jsonObject.get("enable").getAsBoolean();
				}

				if (jsonObject.has("force_show"))
				{
					prompt.enable = jsonObject.get("force_show").getAsBoolean();
				}

				if (jsonObject.has("text"))
				{
					prompt.text = jsonObject.get("text").getAsString();
				}

				if (jsonObject.has("color"))
				{
					prompt.colorStr = jsonObject.get("color").getAsString();
				}

				if (jsonObject.has("duration"))
				{
					prompt.duration = jsonObject.get("duration").getAsInt();
				}

				if (jsonObject.has("cooldown"))
				{
					prompt.cooldown = jsonObject.get("cooldown").getAsInt();
				}

				if (jsonObject.has("time_unit"))
				{
					prompt.timeUnit = jsonObject.get("time_unit").getAsInt();
				}

				if (jsonObject.has("notify"))
				{
					prompt.notify = jsonObject.get("notify").getAsBoolean();
				}

				if (jsonObject.has("times"))
				{
					prompt.times = toCsv(jsonObject.get("times").getAsJsonArray());
				}

				if (jsonObject.has("days_of_week"))
				{
					prompt.daysOfWeek = toCsv(jsonObject.get("days_of_week").getAsJsonArray());
				}

				if (jsonObject.has("dates"))
				{
					prompt.dates = toCsv(jsonObject.get("dates").getAsJsonArray());
				}

				if (jsonObject.has("coordinates"))
				{
					prompt.coordinates = toCsv(jsonObject.get("coordinates").getAsJsonArray());
				}

				if (jsonObject.has("radius"))
				{
					prompt.radius = jsonObject.get("radius").getAsInt();
				}

				if (jsonObject.has("geofences"))
				{
					prompt.geoFences = toCsv(jsonObject.get("geofences").getAsJsonArray());
				}

				if (jsonObject.has("region_ids"))
				{
					prompt.regionIds = toCsv(jsonObject.get("region_ids").getAsJsonArray());
				}

				if (jsonObject.has("npc_ids"))
				{
					prompt.npcIds = toCsv(jsonObject.get("npc_ids").getAsJsonArray());
				}

				if (jsonObject.has("item_ids"))
				{
					prompt.itemIds = toCsv(jsonObject.get("item_ids").getAsJsonArray());
				}

				if (jsonObject.has("chat_patterns"))
				{
					prompt.chatPatterns = toCsv(jsonObject.get("chat_patterns").getAsJsonArray());
				}

				prompts.add(prompt);

				i++;
			}
		}
		catch (Exception exception)
		{
			System.out.println(exception.getMessage());
			return prompts;
		}


		System.out.println("Get reminders took " + (Instant.now().toEpochMilli() - start) + "ms");

		return prompts;
	}

	private List<Prompt> getActivePrompts(List<Prompt> prompts)
	{
		long start = Instant.now().toEpochMilli();

		List<Prompt> activeList = new ArrayList<>();

		activePrompts.addAll(inactivePrompts);
		inactivePrompts.clear();

		for (Prompt prompt : prompts)
		{
			if (prompt.forceShow)
			{
				activeList.add(prompt);
			}
		}

		for (Prompt prompt : prompts)
		{
			if (prompt.enable && !prompt.forceShow)
			{
				if (!((checkTimes(prompt.times) && !matchesTimes(prompt.times)) ||
					(checkDaysOfWeek(prompt.daysOfWeek) && !matchesDaysOfWeek(prompt.daysOfWeek)) ||
					(checkDates(prompt.dates) && !matchesDates(prompt.dates)) ||
					(checkCoordinates(prompt.coordinates) && !matchesCoordinates(prompt.coordinates, prompt.radius)) ||
					(checkGeoFences(prompt.geoFences) && !matchesGeoFences(prompt.geoFences)) ||
					(checkRegions(prompt.regionIds) && !matchesRegions(prompt.regionIds)) ||
					(checkNpcIds(prompt.npcIds) && !matchesNpcIds(prompt.npcIds)) ||
					(checkItemIds(prompt.itemIds) && !matchesItemIds(prompt.itemIds)) ||
					(checkChatPatterns(prompt.chatPatterns) && !matchesChatPatterns(prompt.chatPatterns)))
				)
				{
					Prompt currentActive = currentActiveReminder(prompt.id);
					if (currentActive != null)
					{
						prompt.posted = currentActive.posted;
						prompt.active = currentActive.active;
					}
					else
					{
						prompt.posted = Instant.now().toEpochMilli();
						prompt.active = true;

						clientThreadProvider.get().invoke(() ->
								client.playSoundEffect(getSound(prompt.id).getId(), SoundEffectVolume.HIGH)
						);

						if (prompt.notify && prompt.lastNotified <= Instant.now().toEpochMilli() - 30000)
						{
							notifier.notify(prompt.text);
							prompt.lastNotified = Instant.now().toEpochMilli();
						}
					}

					if (Instant.now().toEpochMilli() >= prompt.posted
							+ prompt.getDurationMillis() + prompt.getCooldownMillis())
					{
						prompt.active = true;
						prompt.posted = Instant.now().toEpochMilli();

						if (prompt.cooldown > 0)
						{
							clientThreadProvider.get().invoke(() ->
									client.playSoundEffect(getSound(prompt.id).getId(), SoundEffectVolume.HIGH)
							);
						}

						if (prompt.notify && prompt.lastNotified <= Instant.now().toEpochMilli() - 30000)
						{
							notifier.notify(prompt.text);
							prompt.lastNotified = Instant.now().toEpochMilli();
						}

						activeList.add(prompt);
						continue;
					}
				}

				if (prompt.posted > 0)
				{
					if (Instant.now().toEpochMilli() >= prompt.posted
							+ prompt.getDurationMillis() + prompt.getCooldownMillis())
					{
						continue;
					}

					prompt.active = Instant.now().toEpochMilli() < prompt.posted
							+ prompt.getDurationMillis();

					if (prompt.active)
					{
						activeList.add(prompt);
					}
					else
					{
						inactivePrompts.add(prompt);
					}
				}
			}
		}

		chatMessages.clear();

		System.out.println("Get active took " + (Instant.now().toEpochMilli() - start) + "ms");

		return activeList;
	}

	private Prompt currentActiveReminder(int id)
	{
		for (Prompt prompt : activePrompts)
		{
			if (prompt.id == id)
			{
				return prompt;
			}
		}
		return null;
	}

	private String toCsv(JsonArray jsonArray)
	{
		StringBuilder sb = new StringBuilder();

		for (JsonElement jsonElement: jsonArray)
		{
			sb.append(jsonElement.getAsString());
			sb.append(",");
		}

		return sb.substring(0, sb.length() - 1);
	}

	private boolean checkTimes(String times)
	{
		return !times.isEmpty();
	}

	private boolean matchesTimes(String times)
	{
		try {
			String [] timeRanges = times.split(",");

			for (String timeRangeStr: timeRanges)
			{
				LocalDateTime startDateTime;
				LocalDateTime endDateTime;

				if (!timeRangeStr.contains("-"))
				{
					startDateTime = toLocalDateTime(timeRangeStr);
					endDateTime = startDateTime.plusMinutes(30);
				}
				else
				{
					String [] timeRangeParts = timeRangeStr.split("-");

					startDateTime = toLocalDateTime(timeRangeParts[0]);
					endDateTime = toLocalDateTime(timeRangeParts[1]);
				}

				if (dateTime.isAfter(startDateTime) && dateTime.isBefore(endDateTime))
				{
					return true;
				}
			}

			return false;
		}
		catch (Exception exception)
		{
			System.out.println(exception.getMessage());
			return false;
		}
	}

	private LocalDateTime toLocalDateTime(String amPmTime)
	{
		String [] timeParts = amPmTime.split(":");

		int h = Integer.parseInt(timeParts[0].trim());

		String ma = timeParts[1]
				.toLowerCase()
				.trim();

		int m = Integer.parseInt(ma
				.replace("am", "")
				.replace("pm", ""));


		String aStr = ma
				.substring(ma.length() - 2);

		if (aStr.equals("pm"))
		{
			if (h != 12)
			{
				h += 12;
			}
		}
		else
		{
			if (h == 12)
			{
				h = 0;
			}
		}

		return LocalDate.now(ZoneId.systemDefault()).atTime(h, m);
	}

	private boolean checkDaysOfWeek(String daysOfWeek)
	{
		return !daysOfWeek.isEmpty();
	}

	private boolean matchesDaysOfWeek(String daysOfWeekStr)
	{
		try {
			String [] daysOfWeek = daysOfWeekStr.split(",");

			for (String dayOfWeek: daysOfWeek)
			{
				String lcDayOfWeek = dayOfWeek.toLowerCase();
				int d = 0;
				if (lcDayOfWeek.startsWith("mo")) d = 1;
				else if (lcDayOfWeek.startsWith("tu")) d = 2;
				else if (lcDayOfWeek.startsWith("we")) d = 3;
				else if (lcDayOfWeek.startsWith("th")) d = 4;
				else if (lcDayOfWeek.startsWith("fr")) d = 5;
				else if (lcDayOfWeek.startsWith("sa")) d = 6;
				else if (lcDayOfWeek.startsWith("su")) d = 7;

				boolean matches = dateTime.get(ChronoField.DAY_OF_WEEK) == d;

				if (matches)
				{
					return true;
				}
			}

			return false;
		}
		catch (Exception exception)
		{
			System.out.println(exception.getMessage());
			return false;
		}
	}

	private boolean checkDates(String dates)
	{
		return !dates.isEmpty();
	}

	private boolean matchesDates(String datesStr)
	{
		try {
			String [] dateRanges = datesStr.split(",");

			for (String dateRangeOrStr: dateRanges)
			{
				LocalDate startLocalDate;
				LocalDate endLocalDate;

				if (dateRangeOrStr.contains("-"))
				{
					String [] dateRangeParts = dateRangeOrStr.split("-");

					startLocalDate = toLocalDate(dateRangeParts[0]);
					endLocalDate = toLocalDate(dateRangeParts[1]);
				}
				else
				{
					startLocalDate = toLocalDate(dateRangeOrStr);
					endLocalDate = toLocalDate(dateRangeOrStr);
				}

				if (startLocalDate.atStartOfDay().isBefore(dateTime)
						&& endLocalDate.plusDays(1).atStartOfDay().isAfter(dateTime))
				{
					return true;
				}
			}

			return false;
		}
		catch (Exception exception)
		{
			System.out.println(exception.getMessage());
			return false;
		}
	}

	private LocalDate toLocalDate(String dateStr)
	{
		String [] dateParts = dateStr.split("/");

		int m = Integer.parseInt(dateParts[0].trim());
		int d = Integer.parseInt(dateParts[1].trim());

		if (dateParts.length > 2)
		{
			int y = Integer.parseInt(dateParts[2].trim());
			return LocalDate.of(y + 2000, m, d);
		}

		return LocalDate.of(dateTime.getYear(), m, d);
	}

	private boolean checkCoordinates(String coordinates)
	{
		return !coordinates.isEmpty();
	}

	private boolean matchesCoordinates(String coordinatesStr, int radius)
	{
		try {
			String [] coordinates = coordinatesStr.split(",");

			int x = 0;
			for (int i = 0; i < coordinates.length; i++)
			{
				String xOrY = coordinates[i]
						.replace("(", "")
						.replace(")", "")
						.trim();

				if (i % 2 == 0)
				{
					x = Integer.parseInt(xOrY);
					continue;
				}

				int y = Integer.parseInt(xOrY);

				double distance = Math.sqrt(
						Math.pow(x - worldPos.getX(), 2) +
								Math.pow(y - worldPos.getY(), 2)
				);

				boolean matches = distance <= radius;

				if (matches)
				{
					return true;
				}
			}

			return false;
		}
		catch (Exception exception)
		{
			System.out.println(exception.getMessage());
			return false;
		}
	}

	private boolean checkGeoFences(String geoFences)
	{
		return !geoFences.isEmpty();
	}

	private boolean matchesGeoFences(String geoFences)
	{
		try {
			String [] coordinates = geoFences.split(",");

			int left = 0;
			int top = 0;
			int right = 0;
			for (int i = 0; i < coordinates.length; i++)
			{
				String side = coordinates[i]
						.replace("(", "")
						.replace(")", "")
						.trim();

				if (i % 4 == 0)
				{
					left = Integer.parseInt(side);
					continue;
				}
				else if (i % 4 == 1)
				{
					top = Integer.parseInt(side);
					continue;
				}
				else if (i % 4 == 2)
				{
					right = Integer.parseInt(side);
					continue;
				}

				int bottom = Integer.parseInt(side);

				boolean matches = worldPos.getX() >= left && worldPos.getX() <= right
						&& worldPos.getY() >= bottom && worldPos.getY() <= top;

				if (matches)
				{
					return true;
				}
			}

			return false;
		}
		catch (Exception exception)
		{
			System.out.println(exception.getMessage());
			return false;
		}
	}

	private boolean checkRegions(String regionIds)
	{
		return !regionIds.isEmpty();
	}

	private boolean matchesRegions(String regionIds)
	{
		try {
			String [] ids = regionIds.split(",");

			for (String idStr: ids)
			{
				int id = Integer.parseInt(idStr.trim());

				boolean matches = regionId == id;

				if (matches)
				{
					return true;
				}
			}

			return false;
		}
		catch (Exception exception)
		{
			return false;
		}
	}

	private boolean checkNpcIds(String npcIds)
	{
		return !npcIds.isEmpty();
	}

	private boolean matchesNpcIds(String npcIdsStr)
	{
		try {
			String [] npcIds = npcIdsStr.split(",");

			for (String npcIdStr: npcIds)
			{
				int npcId = Integer.parseInt(npcIdStr.trim());

				boolean matches = false;
				for (NPC npc: npcs)
				{
					if (npc.getId() == npcId) {
						matches = true;
						break;
					}
				}

				if (matches)
				{
					return true;
				}
			}

			return false;
		}
		catch (Exception exception)
		{
			return false;
		}
	}

	private boolean checkItemIds(String itemIds)
	{
		return !itemIds.isEmpty();
	}

	private boolean matchesItemIds(String itemIdsStr)
	{
		try {
			String [] itemIds = itemIdsStr.split(",");

			for (String itemIdStr: itemIds)
			{
				int itemId = Integer.parseInt(itemIdStr.trim());

				boolean matches = false;
				for (ItemComposition item: items)
				{
					if (item.getId() == itemId) {
						matches = true;
						break;
					}
				}

				if (matches)
				{
					return true;
				}
			}

			return false;
		}
		catch (Exception exception)
		{
			return false;
		}
	}

	private boolean checkChatPatterns(String chatPatterns)
	{
		return !chatPatterns.isEmpty();
	}

	private boolean matchesChatPatterns(String chatPatternsStr)
	{
		try {
			String [] chatPatterns = chatPatternsStr.split(",");

			for (String chatPattern: chatPatterns)
			{
				chatPattern = chatPattern.trim();

				for (ChatMessage chatMessage: chatMessages)
				{
					Matcher matcher = Pattern.compile(chatPattern)
							.matcher(chatMessage.getMessage());

					if (matcher.find()) {
						return true;
					}
				}
			}

			return false;
		}
		catch (Exception exception)
		{
			return false;
		}
	}

	boolean isEnable(int reminderId)
	{
		if (reminderId == 1) return config.prompt1Enable();
		else if (reminderId == 2) return config.prompt2Enable();
		else if (reminderId == 3) return config.prompt3Enable();
		else if (reminderId == 4) return config.prompt4Enable();
		else if (reminderId == 5) return config.prompt5Enable();
		else if (reminderId == 6) return config.prompt6Enable();
		else if (reminderId == 7) return config.prompt7Enable();
		else if (reminderId == 8) return config.prompt8Enable();
		else if (reminderId == 9) return config.prompt9Enable();
		else if (reminderId == 10) return config.prompt10Enable();
		else if (reminderId == 11) return config.prompt11Enable();
		else if (reminderId == 12) return config.prompt12Enable();
		else if (reminderId == 13) return config.prompt13Enable();
		else if (reminderId == 14) return config.prompt14Enable();
		else if (reminderId == 15) return config.prompt15Enable();
		else if (reminderId == 16) return config.prompt16Enable();
		else if (reminderId == 17) return config.prompt17Enable();
		else if (reminderId == 18) return config.prompt18Enable();
		else if (reminderId == 19) return config.prompt19Enable();
		else if (reminderId == 20) return config.prompt20Enable();
		else if (reminderId == 21) return config.prompt21Enable();
		else if (reminderId == 22) return config.prompt22Enable();
		else if (reminderId == 23) return config.prompt23Enable();
		else if (reminderId == 24) return config.prompt24Enable();
		else if (reminderId == 25) return config.prompt25Enable();
		else if (reminderId == 26) return config.prompt26Enable();
		else if (reminderId == 27) return config.prompt27Enable();
		else if (reminderId == 28) return config.prompt28Enable();
		else if (reminderId == 29) return config.prompt29Enable();
		else if (reminderId == 30) return config.prompt30Enable();
		else if (reminderId == 31) return config.prompt31Enable();
		else if (reminderId == 32) return config.prompt32Enable();
		else if (reminderId == 33) return config.prompt33Enable();
		else if (reminderId == 34) return config.prompt34Enable();
		else if (reminderId == 35) return config.prompt35Enable();
		else if (reminderId == 36) return config.prompt36Enable();
		else if (reminderId == 37) return config.prompt37Enable();
		else if (reminderId == 38) return config.prompt38Enable();
		else if (reminderId == 39) return config.prompt39Enable();
		else if (reminderId == 40) return config.prompt40Enable();
		else if (reminderId == 41) return config.prompt41Enable();
		else if (reminderId == 42) return config.prompt42Enable();
		else if (reminderId == 43) return config.prompt43Enable();
		else if (reminderId == 44) return config.prompt44Enable();
		else if (reminderId == 45) return config.prompt45Enable();
		else if (reminderId == 46) return config.prompt46Enable();
		else if (reminderId == 47) return config.prompt47Enable();
		else if (reminderId == 48) return config.prompt48Enable();
		else if (reminderId == 49) return config.prompt49Enable();
		else if (reminderId == 50) return config.prompt50Enable();
		else if (reminderId == 51) return config.prompt51Enable();
		else if (reminderId == 52) return config.prompt52Enable();
		else if (reminderId == 53) return config.prompt53Enable();
		else if (reminderId == 54) return config.prompt54Enable();
		else if (reminderId == 55) return config.prompt55Enable();
		else if (reminderId == 56) return config.prompt56Enable();
		else if (reminderId == 57) return config.prompt57Enable();
		else if (reminderId == 58) return config.prompt58Enable();
		else if (reminderId == 59) return config.prompt59Enable();
		else if (reminderId == 60) return config.prompt60Enable();
		else if (reminderId == 61) return config.prompt61Enable();
		else if (reminderId == 62) return config.prompt62Enable();
		else if (reminderId == 63) return config.prompt63Enable();
		else if (reminderId == 64) return config.prompt64Enable();
		else if (reminderId == 65) return config.prompt65Enable();
		else if (reminderId == 66) return config.prompt66Enable();
		else if (reminderId == 67) return config.prompt67Enable();
		else if (reminderId == 68) return config.prompt68Enable();
		else if (reminderId == 69) return config.prompt69Enable();
		else if (reminderId == 70) return config.prompt70Enable();
		else if (reminderId == 71) return config.prompt71Enable();
		else if (reminderId == 72) return config.prompt72Enable();
		else if (reminderId == 73) return config.prompt73Enable();
		else if (reminderId == 74) return config.prompt74Enable();
		else if (reminderId == 75) return config.prompt75Enable();
		else if (reminderId == 76) return config.prompt76Enable();
		else if (reminderId == 77) return config.prompt77Enable();
		else if (reminderId == 78) return config.prompt78Enable();
		else if (reminderId == 79) return config.prompt79Enable();
		else if (reminderId == 80) return config.prompt80Enable();
		else if (reminderId == 81) return config.prompt81Enable();
		else if (reminderId == 82) return config.prompt82Enable();
		else if (reminderId == 83) return config.prompt83Enable();
		else if (reminderId == 84) return config.prompt84Enable();
		else if (reminderId == 85) return config.prompt85Enable();
		else if (reminderId == 86) return config.prompt86Enable();
		else if (reminderId == 87) return config.prompt87Enable();
		else if (reminderId == 88) return config.prompt88Enable();
		else if (reminderId == 89) return config.prompt89Enable();
		else if (reminderId == 90) return config.prompt90Enable();
		else if (reminderId == 91) return config.prompt91Enable();
		else if (reminderId == 92) return config.prompt92Enable();
		else if (reminderId == 93) return config.prompt93Enable();
		else if (reminderId == 94) return config.prompt94Enable();
		else if (reminderId == 95) return config.prompt95Enable();
		else if (reminderId == 96) return config.prompt96Enable();
		else if (reminderId == 97) return config.prompt97Enable();
		else if (reminderId == 98) return config.prompt98Enable();
		else if (reminderId == 99) return config.prompt99Enable();
		else if (reminderId == 100) return config.prompt100Enable();
		else return false;
	}

	String getText(int reminderId)
	{
		if (reminderId == 1) return config.prompt1Text();
		else if (reminderId == 2) return config.prompt2Text();
		else if (reminderId == 3) return config.prompt3Text();
		else if (reminderId == 4) return config.prompt4Text();
		else if (reminderId == 5) return config.prompt5Text();
		else if (reminderId == 6) return config.prompt6Text();
		else if (reminderId == 7) return config.prompt7Text();
		else if (reminderId == 8) return config.prompt8Text();
		else if (reminderId == 9) return config.prompt9Text();
		else if (reminderId == 10) return config.prompt10Text();
		else if (reminderId == 11) return config.prompt11Text();
		else if (reminderId == 12) return config.prompt12Text();
		else if (reminderId == 13) return config.prompt13Text();
		else if (reminderId == 14) return config.prompt14Text();
		else if (reminderId == 15) return config.prompt15Text();
		else if (reminderId == 16) return config.prompt16Text();
		else if (reminderId == 17) return config.prompt17Text();
		else if (reminderId == 18) return config.prompt18Text();
		else if (reminderId == 19) return config.prompt19Text();
		else if (reminderId == 20) return config.prompt20Text();
		else if (reminderId == 21) return config.prompt21Text();
		else if (reminderId == 22) return config.prompt22Text();
		else if (reminderId == 23) return config.prompt23Text();
		else if (reminderId == 24) return config.prompt24Text();
		else if (reminderId == 25) return config.prompt25Text();
		else if (reminderId == 26) return config.prompt26Text();
		else if (reminderId == 27) return config.prompt27Text();
		else if (reminderId == 28) return config.prompt28Text();
		else if (reminderId == 29) return config.prompt29Text();
		else if (reminderId == 30) return config.prompt30Text();
		else if (reminderId == 31) return config.prompt31Text();
		else if (reminderId == 32) return config.prompt32Text();
		else if (reminderId == 33) return config.prompt33Text();
		else if (reminderId == 34) return config.prompt34Text();
		else if (reminderId == 35) return config.prompt35Text();
		else if (reminderId == 36) return config.prompt36Text();
		else if (reminderId == 37) return config.prompt37Text();
		else if (reminderId == 38) return config.prompt38Text();
		else if (reminderId == 39) return config.prompt39Text();
		else if (reminderId == 40) return config.prompt40Text();
		else if (reminderId == 41) return config.prompt41Text();
		else if (reminderId == 42) return config.prompt42Text();
		else if (reminderId == 43) return config.prompt43Text();
		else if (reminderId == 44) return config.prompt44Text();
		else if (reminderId == 45) return config.prompt45Text();
		else if (reminderId == 46) return config.prompt46Text();
		else if (reminderId == 47) return config.prompt47Text();
		else if (reminderId == 48) return config.prompt48Text();
		else if (reminderId == 49) return config.prompt49Text();
		else if (reminderId == 50) return config.prompt50Text();
		else if (reminderId == 51) return config.prompt51Text();
		else if (reminderId == 52) return config.prompt52Text();
		else if (reminderId == 53) return config.prompt53Text();
		else if (reminderId == 54) return config.prompt54Text();
		else if (reminderId == 55) return config.prompt55Text();
		else if (reminderId == 56) return config.prompt56Text();
		else if (reminderId == 57) return config.prompt57Text();
		else if (reminderId == 58) return config.prompt58Text();
		else if (reminderId == 59) return config.prompt59Text();
		else if (reminderId == 60) return config.prompt60Text();
		else if (reminderId == 61) return config.prompt61Text();
		else if (reminderId == 62) return config.prompt62Text();
		else if (reminderId == 63) return config.prompt63Text();
		else if (reminderId == 64) return config.prompt64Text();
		else if (reminderId == 65) return config.prompt65Text();
		else if (reminderId == 66) return config.prompt66Text();
		else if (reminderId == 67) return config.prompt67Text();
		else if (reminderId == 68) return config.prompt68Text();
		else if (reminderId == 69) return config.prompt69Text();
		else if (reminderId == 70) return config.prompt70Text();
		else if (reminderId == 71) return config.prompt71Text();
		else if (reminderId == 72) return config.prompt72Text();
		else if (reminderId == 73) return config.prompt73Text();
		else if (reminderId == 74) return config.prompt74Text();
		else if (reminderId == 75) return config.prompt75Text();
		else if (reminderId == 76) return config.prompt76Text();
		else if (reminderId == 77) return config.prompt77Text();
		else if (reminderId == 78) return config.prompt78Text();
		else if (reminderId == 79) return config.prompt79Text();
		else if (reminderId == 80) return config.prompt80Text();
		else if (reminderId == 81) return config.prompt81Text();
		else if (reminderId == 82) return config.prompt82Text();
		else if (reminderId == 83) return config.prompt83Text();
		else if (reminderId == 84) return config.prompt84Text();
		else if (reminderId == 85) return config.prompt85Text();
		else if (reminderId == 86) return config.prompt86Text();
		else if (reminderId == 87) return config.prompt87Text();
		else if (reminderId == 88) return config.prompt88Text();
		else if (reminderId == 89) return config.prompt89Text();
		else if (reminderId == 90) return config.prompt90Text();
		else if (reminderId == 91) return config.prompt91Text();
		else if (reminderId == 92) return config.prompt92Text();
		else if (reminderId == 93) return config.prompt93Text();
		else if (reminderId == 94) return config.prompt94Text();
		else if (reminderId == 95) return config.prompt95Text();
		else if (reminderId == 96) return config.prompt96Text();
		else if (reminderId == 97) return config.prompt97Text();
		else if (reminderId == 98) return config.prompt98Text();
		else if (reminderId == 99) return config.prompt99Text();
		else if (reminderId == 100) return config.prompt100Text();
		else return "";
	}

	Color getColor(int reminderId)
	{
		if (reminderId == 1) return config.prompt1Color();
		else if (reminderId == 2) return config.prompt2Color();
		else if (reminderId == 3) return config.prompt3Color();
		else if (reminderId == 4) return config.prompt4Color();
		else if (reminderId == 5) return config.prompt5Color();
		else if (reminderId == 6) return config.prompt6Color();
		else if (reminderId == 7) return config.prompt7Color();
		else if (reminderId == 8) return config.prompt8Color();
		else if (reminderId == 9) return config.prompt9Color();
		else if (reminderId == 10) return config.prompt10Color();
		else if (reminderId == 11) return config.prompt11Color();
		else if (reminderId == 12) return config.prompt12Color();
		else if (reminderId == 13) return config.prompt13Color();
		else if (reminderId == 14) return config.prompt14Color();
		else if (reminderId == 15) return config.prompt15Color();
		else if (reminderId == 16) return config.prompt16Color();
		else if (reminderId == 17) return config.prompt17Color();
		else if (reminderId == 18) return config.prompt18Color();
		else if (reminderId == 19) return config.prompt19Color();
		else if (reminderId == 20) return config.prompt20Color();
		else if (reminderId == 21) return config.prompt21Color();
		else if (reminderId == 22) return config.prompt22Color();
		else if (reminderId == 23) return config.prompt23Color();
		else if (reminderId == 24) return config.prompt24Color();
		else if (reminderId == 25) return config.prompt25Color();
		else if (reminderId == 26) return config.prompt26Color();
		else if (reminderId == 27) return config.prompt27Color();
		else if (reminderId == 28) return config.prompt28Color();
		else if (reminderId == 29) return config.prompt29Color();
		else if (reminderId == 30) return config.prompt30Color();
		else if (reminderId == 31) return config.prompt31Color();
		else if (reminderId == 32) return config.prompt32Color();
		else if (reminderId == 33) return config.prompt33Color();
		else if (reminderId == 34) return config.prompt34Color();
		else if (reminderId == 35) return config.prompt35Color();
		else if (reminderId == 36) return config.prompt36Color();
		else if (reminderId == 37) return config.prompt37Color();
		else if (reminderId == 38) return config.prompt38Color();
		else if (reminderId == 39) return config.prompt39Color();
		else if (reminderId == 40) return config.prompt40Color();
		else if (reminderId == 41) return config.prompt41Color();
		else if (reminderId == 42) return config.prompt42Color();
		else if (reminderId == 43) return config.prompt43Color();
		else if (reminderId == 44) return config.prompt44Color();
		else if (reminderId == 45) return config.prompt45Color();
		else if (reminderId == 46) return config.prompt46Color();
		else if (reminderId == 47) return config.prompt47Color();
		else if (reminderId == 48) return config.prompt48Color();
		else if (reminderId == 49) return config.prompt49Color();
		else if (reminderId == 50) return config.prompt50Color();
		else if (reminderId == 51) return config.prompt51Color();
		else if (reminderId == 52) return config.prompt52Color();
		else if (reminderId == 53) return config.prompt53Color();
		else if (reminderId == 54) return config.prompt54Color();
		else if (reminderId == 55) return config.prompt55Color();
		else if (reminderId == 56) return config.prompt56Color();
		else if (reminderId == 57) return config.prompt57Color();
		else if (reminderId == 58) return config.prompt58Color();
		else if (reminderId == 59) return config.prompt59Color();
		else if (reminderId == 60) return config.prompt60Color();
		else if (reminderId == 61) return config.prompt61Color();
		else if (reminderId == 62) return config.prompt62Color();
		else if (reminderId == 63) return config.prompt63Color();
		else if (reminderId == 64) return config.prompt64Color();
		else if (reminderId == 65) return config.prompt65Color();
		else if (reminderId == 66) return config.prompt66Color();
		else if (reminderId == 67) return config.prompt67Color();
		else if (reminderId == 68) return config.prompt68Color();
		else if (reminderId == 69) return config.prompt69Color();
		else if (reminderId == 70) return config.prompt70Color();
		else if (reminderId == 71) return config.prompt71Color();
		else if (reminderId == 72) return config.prompt72Color();
		else if (reminderId == 73) return config.prompt73Color();
		else if (reminderId == 74) return config.prompt74Color();
		else if (reminderId == 75) return config.prompt75Color();
		else if (reminderId == 76) return config.prompt76Color();
		else if (reminderId == 77) return config.prompt77Color();
		else if (reminderId == 78) return config.prompt78Color();
		else if (reminderId == 79) return config.prompt79Color();
		else if (reminderId == 80) return config.prompt80Color();
		else if (reminderId == 81) return config.prompt81Color();
		else if (reminderId == 82) return config.prompt82Color();
		else if (reminderId == 83) return config.prompt83Color();
		else if (reminderId == 84) return config.prompt84Color();
		else if (reminderId == 85) return config.prompt85Color();
		else if (reminderId == 86) return config.prompt86Color();
		else if (reminderId == 87) return config.prompt87Color();
		else if (reminderId == 88) return config.prompt88Color();
		else if (reminderId == 89) return config.prompt89Color();
		else if (reminderId == 90) return config.prompt90Color();
		else if (reminderId == 91) return config.prompt91Color();
		else if (reminderId == 92) return config.prompt92Color();
		else if (reminderId == 93) return config.prompt93Color();
		else if (reminderId == 94) return config.prompt94Color();
		else if (reminderId == 95) return config.prompt95Color();
		else if (reminderId == 96) return config.prompt96Color();
		else if (reminderId == 97) return config.prompt97Color();
		else if (reminderId == 98) return config.prompt98Color();
		else if (reminderId == 99) return config.prompt99Color();
		else if (reminderId == 100) return config.prompt100Color();
		else return Color.WHITE;
	}

	int getDuration(int reminderId)
	{
		if (reminderId == 1) return config.prompt1Duration();
		else if (reminderId == 2) return config.prompt2Duration();
		else if (reminderId == 3) return config.prompt3Duration();
		else if (reminderId == 4) return config.prompt4Duration();
		else if (reminderId == 5) return config.prompt5Duration();
		else if (reminderId == 6) return config.prompt6Duration();
		else if (reminderId == 7) return config.prompt7Duration();
		else if (reminderId == 8) return config.prompt8Duration();
		else if (reminderId == 9) return config.prompt9Duration();
		else if (reminderId == 10) return config.prompt10Duration();
		else if (reminderId == 11) return config.prompt11Duration();
		else if (reminderId == 12) return config.prompt12Duration();
		else if (reminderId == 13) return config.prompt13Duration();
		else if (reminderId == 14) return config.prompt14Duration();
		else if (reminderId == 15) return config.prompt15Duration();
		else if (reminderId == 16) return config.prompt16Duration();
		else if (reminderId == 17) return config.prompt17Duration();
		else if (reminderId == 18) return config.prompt18Duration();
		else if (reminderId == 19) return config.prompt19Duration();
		else if (reminderId == 20) return config.prompt20Duration();
		else if (reminderId == 21) return config.prompt21Duration();
		else if (reminderId == 22) return config.prompt22Duration();
		else if (reminderId == 23) return config.prompt23Duration();
		else if (reminderId == 24) return config.prompt24Duration();
		else if (reminderId == 25) return config.prompt25Duration();
		else if (reminderId == 26) return config.prompt26Duration();
		else if (reminderId == 27) return config.prompt27Duration();
		else if (reminderId == 28) return config.prompt28Duration();
		else if (reminderId == 29) return config.prompt29Duration();
		else if (reminderId == 30) return config.prompt30Duration();
		else if (reminderId == 31) return config.prompt31Duration();
		else if (reminderId == 32) return config.prompt32Duration();
		else if (reminderId == 33) return config.prompt33Duration();
		else if (reminderId == 34) return config.prompt34Duration();
		else if (reminderId == 35) return config.prompt35Duration();
		else if (reminderId == 36) return config.prompt36Duration();
		else if (reminderId == 37) return config.prompt37Duration();
		else if (reminderId == 38) return config.prompt38Duration();
		else if (reminderId == 39) return config.prompt39Duration();
		else if (reminderId == 40) return config.prompt40Duration();
		else if (reminderId == 41) return config.prompt41Duration();
		else if (reminderId == 42) return config.prompt42Duration();
		else if (reminderId == 43) return config.prompt43Duration();
		else if (reminderId == 44) return config.prompt44Duration();
		else if (reminderId == 45) return config.prompt45Duration();
		else if (reminderId == 46) return config.prompt46Duration();
		else if (reminderId == 47) return config.prompt47Duration();
		else if (reminderId == 48) return config.prompt48Duration();
		else if (reminderId == 49) return config.prompt49Duration();
		else if (reminderId == 50) return config.prompt50Duration();
		else if (reminderId == 51) return config.prompt51Duration();
		else if (reminderId == 52) return config.prompt52Duration();
		else if (reminderId == 53) return config.prompt53Duration();
		else if (reminderId == 54) return config.prompt54Duration();
		else if (reminderId == 55) return config.prompt55Duration();
		else if (reminderId == 56) return config.prompt56Duration();
		else if (reminderId == 57) return config.prompt57Duration();
		else if (reminderId == 58) return config.prompt58Duration();
		else if (reminderId == 59) return config.prompt59Duration();
		else if (reminderId == 60) return config.prompt60Duration();
		else if (reminderId == 61) return config.prompt61Duration();
		else if (reminderId == 62) return config.prompt62Duration();
		else if (reminderId == 63) return config.prompt63Duration();
		else if (reminderId == 64) return config.prompt64Duration();
		else if (reminderId == 65) return config.prompt65Duration();
		else if (reminderId == 66) return config.prompt66Duration();
		else if (reminderId == 67) return config.prompt67Duration();
		else if (reminderId == 68) return config.prompt68Duration();
		else if (reminderId == 69) return config.prompt69Duration();
		else if (reminderId == 70) return config.prompt70Duration();
		else if (reminderId == 71) return config.prompt71Duration();
		else if (reminderId == 72) return config.prompt72Duration();
		else if (reminderId == 73) return config.prompt73Duration();
		else if (reminderId == 74) return config.prompt74Duration();
		else if (reminderId == 75) return config.prompt75Duration();
		else if (reminderId == 76) return config.prompt76Duration();
		else if (reminderId == 77) return config.prompt77Duration();
		else if (reminderId == 78) return config.prompt78Duration();
		else if (reminderId == 79) return config.prompt79Duration();
		else if (reminderId == 80) return config.prompt80Duration();
		else if (reminderId == 81) return config.prompt81Duration();
		else if (reminderId == 82) return config.prompt82Duration();
		else if (reminderId == 83) return config.prompt83Duration();
		else if (reminderId == 84) return config.prompt84Duration();
		else if (reminderId == 85) return config.prompt85Duration();
		else if (reminderId == 86) return config.prompt86Duration();
		else if (reminderId == 87) return config.prompt87Duration();
		else if (reminderId == 88) return config.prompt88Duration();
		else if (reminderId == 89) return config.prompt89Duration();
		else if (reminderId == 90) return config.prompt90Duration();
		else if (reminderId == 91) return config.prompt91Duration();
		else if (reminderId == 92) return config.prompt92Duration();
		else if (reminderId == 93) return config.prompt93Duration();
		else if (reminderId == 94) return config.prompt94Duration();
		else if (reminderId == 95) return config.prompt95Duration();
		else if (reminderId == 96) return config.prompt96Duration();
		else if (reminderId == 97) return config.prompt97Duration();
		else if (reminderId == 98) return config.prompt98Duration();
		else if (reminderId == 99) return config.prompt99Duration();
		else if (reminderId == 100) return config.prompt100Duration();
		else return 0;
	}

	int getCooldown(int reminderId)
	{
		if (reminderId == 1) return config.prompt1Cooldown();
		else if (reminderId == 2) return config.prompt2Cooldown();
		else if (reminderId == 3) return config.prompt3Cooldown();
		else if (reminderId == 4) return config.prompt4Cooldown();
		else if (reminderId == 5) return config.prompt5Cooldown();
		else if (reminderId == 6) return config.prompt6Cooldown();
		else if (reminderId == 7) return config.prompt7Cooldown();
		else if (reminderId == 8) return config.prompt8Cooldown();
		else if (reminderId == 9) return config.prompt9Cooldown();
		else if (reminderId == 10) return config.prompt10Cooldown();
		else if (reminderId == 11) return config.prompt11Cooldown();
		else if (reminderId == 12) return config.prompt12Cooldown();
		else if (reminderId == 13) return config.prompt13Cooldown();
		else if (reminderId == 14) return config.prompt14Cooldown();
		else if (reminderId == 15) return config.prompt15Cooldown();
		else if (reminderId == 16) return config.prompt16Cooldown();
		else if (reminderId == 17) return config.prompt17Cooldown();
		else if (reminderId == 18) return config.prompt18Cooldown();
		else if (reminderId == 19) return config.prompt19Cooldown();
		else if (reminderId == 20) return config.prompt20Cooldown();
		else if (reminderId == 21) return config.prompt21Cooldown();
		else if (reminderId == 22) return config.prompt22Cooldown();
		else if (reminderId == 23) return config.prompt23Cooldown();
		else if (reminderId == 24) return config.prompt24Cooldown();
		else if (reminderId == 25) return config.prompt25Cooldown();
		else if (reminderId == 26) return config.prompt26Cooldown();
		else if (reminderId == 27) return config.prompt27Cooldown();
		else if (reminderId == 28) return config.prompt28Cooldown();
		else if (reminderId == 29) return config.prompt29Cooldown();
		else if (reminderId == 30) return config.prompt30Cooldown();
		else if (reminderId == 31) return config.prompt31Cooldown();
		else if (reminderId == 32) return config.prompt32Cooldown();
		else if (reminderId == 33) return config.prompt33Cooldown();
		else if (reminderId == 34) return config.prompt34Cooldown();
		else if (reminderId == 35) return config.prompt35Cooldown();
		else if (reminderId == 36) return config.prompt36Cooldown();
		else if (reminderId == 37) return config.prompt37Cooldown();
		else if (reminderId == 38) return config.prompt38Cooldown();
		else if (reminderId == 39) return config.prompt39Cooldown();
		else if (reminderId == 40) return config.prompt40Cooldown();
		else if (reminderId == 41) return config.prompt41Cooldown();
		else if (reminderId == 42) return config.prompt42Cooldown();
		else if (reminderId == 43) return config.prompt43Cooldown();
		else if (reminderId == 44) return config.prompt44Cooldown();
		else if (reminderId == 45) return config.prompt45Cooldown();
		else if (reminderId == 46) return config.prompt46Cooldown();
		else if (reminderId == 47) return config.prompt47Cooldown();
		else if (reminderId == 48) return config.prompt48Cooldown();
		else if (reminderId == 49) return config.prompt49Cooldown();
		else if (reminderId == 50) return config.prompt50Cooldown();
		else if (reminderId == 51) return config.prompt51Cooldown();
		else if (reminderId == 52) return config.prompt52Cooldown();
		else if (reminderId == 53) return config.prompt53Cooldown();
		else if (reminderId == 54) return config.prompt54Cooldown();
		else if (reminderId == 55) return config.prompt55Cooldown();
		else if (reminderId == 56) return config.prompt56Cooldown();
		else if (reminderId == 57) return config.prompt57Cooldown();
		else if (reminderId == 58) return config.prompt58Cooldown();
		else if (reminderId == 59) return config.prompt59Cooldown();
		else if (reminderId == 60) return config.prompt60Cooldown();
		else if (reminderId == 61) return config.prompt61Cooldown();
		else if (reminderId == 62) return config.prompt62Cooldown();
		else if (reminderId == 63) return config.prompt63Cooldown();
		else if (reminderId == 64) return config.prompt64Cooldown();
		else if (reminderId == 65) return config.prompt65Cooldown();
		else if (reminderId == 66) return config.prompt66Cooldown();
		else if (reminderId == 67) return config.prompt67Cooldown();
		else if (reminderId == 68) return config.prompt68Cooldown();
		else if (reminderId == 69) return config.prompt69Cooldown();
		else if (reminderId == 70) return config.prompt70Cooldown();
		else if (reminderId == 71) return config.prompt71Cooldown();
		else if (reminderId == 72) return config.prompt72Cooldown();
		else if (reminderId == 73) return config.prompt73Cooldown();
		else if (reminderId == 74) return config.prompt74Cooldown();
		else if (reminderId == 75) return config.prompt75Cooldown();
		else if (reminderId == 76) return config.prompt76Cooldown();
		else if (reminderId == 77) return config.prompt77Cooldown();
		else if (reminderId == 78) return config.prompt78Cooldown();
		else if (reminderId == 79) return config.prompt79Cooldown();
		else if (reminderId == 80) return config.prompt80Cooldown();
		else if (reminderId == 81) return config.prompt81Cooldown();
		else if (reminderId == 82) return config.prompt82Cooldown();
		else if (reminderId == 83) return config.prompt83Cooldown();
		else if (reminderId == 84) return config.prompt84Cooldown();
		else if (reminderId == 85) return config.prompt85Cooldown();
		else if (reminderId == 86) return config.prompt86Cooldown();
		else if (reminderId == 87) return config.prompt87Cooldown();
		else if (reminderId == 88) return config.prompt88Cooldown();
		else if (reminderId == 89) return config.prompt89Cooldown();
		else if (reminderId == 90) return config.prompt90Cooldown();
		else if (reminderId == 91) return config.prompt91Cooldown();
		else if (reminderId == 92) return config.prompt92Cooldown();
		else if (reminderId == 93) return config.prompt93Cooldown();
		else if (reminderId == 94) return config.prompt94Cooldown();
		else if (reminderId == 95) return config.prompt95Cooldown();
		else if (reminderId == 96) return config.prompt96Cooldown();
		else if (reminderId == 97) return config.prompt97Cooldown();
		else if (reminderId == 98) return config.prompt98Cooldown();
		else if (reminderId == 99) return config.prompt99Cooldown();
		else if (reminderId == 100) return config.prompt100Cooldown();
		else return 0;
	}

	TimeUnit getTimeUnit(int reminderId)
	{
		if (reminderId == 1) return config.prompt1TimeUnit();
		else if (reminderId == 2) return config.prompt2TimeUnit();
		else if (reminderId == 3) return config.prompt3TimeUnit();
		else if (reminderId == 4) return config.prompt4TimeUnit();
		else if (reminderId == 5) return config.prompt5TimeUnit();
		else if (reminderId == 6) return config.prompt6TimeUnit();
		else if (reminderId == 7) return config.prompt7TimeUnit();
		else if (reminderId == 8) return config.prompt8TimeUnit();
		else if (reminderId == 9) return config.prompt9TimeUnit();
		else if (reminderId == 10) return config.prompt10TimeUnit();
		else if (reminderId == 11) return config.prompt11TimeUnit();
		else if (reminderId == 12) return config.prompt12TimeUnit();
		else if (reminderId == 13) return config.prompt13TimeUnit();
		else if (reminderId == 14) return config.prompt14TimeUnit();
		else if (reminderId == 15) return config.prompt15TimeUnit();
		else if (reminderId == 16) return config.prompt16TimeUnit();
		else if (reminderId == 17) return config.prompt17TimeUnit();
		else if (reminderId == 18) return config.prompt18TimeUnit();
		else if (reminderId == 19) return config.prompt19TimeUnit();
		else if (reminderId == 20) return config.prompt20TimeUnit();
		else if (reminderId == 21) return config.prompt21TimeUnit();
		else if (reminderId == 22) return config.prompt22TimeUnit();
		else if (reminderId == 23) return config.prompt23TimeUnit();
		else if (reminderId == 24) return config.prompt24TimeUnit();
		else if (reminderId == 25) return config.prompt25TimeUnit();
		else if (reminderId == 26) return config.prompt26TimeUnit();
		else if (reminderId == 27) return config.prompt27TimeUnit();
		else if (reminderId == 28) return config.prompt28TimeUnit();
		else if (reminderId == 29) return config.prompt29TimeUnit();
		else if (reminderId == 30) return config.prompt30TimeUnit();
		else if (reminderId == 31) return config.prompt31TimeUnit();
		else if (reminderId == 32) return config.prompt32TimeUnit();
		else if (reminderId == 33) return config.prompt33TimeUnit();
		else if (reminderId == 34) return config.prompt34TimeUnit();
		else if (reminderId == 35) return config.prompt35TimeUnit();
		else if (reminderId == 36) return config.prompt36TimeUnit();
		else if (reminderId == 37) return config.prompt37TimeUnit();
		else if (reminderId == 38) return config.prompt38TimeUnit();
		else if (reminderId == 39) return config.prompt39TimeUnit();
		else if (reminderId == 40) return config.prompt40TimeUnit();
		else if (reminderId == 41) return config.prompt41TimeUnit();
		else if (reminderId == 42) return config.prompt42TimeUnit();
		else if (reminderId == 43) return config.prompt43TimeUnit();
		else if (reminderId == 44) return config.prompt44TimeUnit();
		else if (reminderId == 45) return config.prompt45TimeUnit();
		else if (reminderId == 46) return config.prompt46TimeUnit();
		else if (reminderId == 47) return config.prompt47TimeUnit();
		else if (reminderId == 48) return config.prompt48TimeUnit();
		else if (reminderId == 49) return config.prompt49TimeUnit();
		else if (reminderId == 50) return config.prompt50TimeUnit();
		else if (reminderId == 51) return config.prompt51TimeUnit();
		else if (reminderId == 52) return config.prompt52TimeUnit();
		else if (reminderId == 53) return config.prompt53TimeUnit();
		else if (reminderId == 54) return config.prompt54TimeUnit();
		else if (reminderId == 55) return config.prompt55TimeUnit();
		else if (reminderId == 56) return config.prompt56TimeUnit();
		else if (reminderId == 57) return config.prompt57TimeUnit();
		else if (reminderId == 58) return config.prompt58TimeUnit();
		else if (reminderId == 59) return config.prompt59TimeUnit();
		else if (reminderId == 60) return config.prompt60TimeUnit();
		else if (reminderId == 61) return config.prompt61TimeUnit();
		else if (reminderId == 62) return config.prompt62TimeUnit();
		else if (reminderId == 63) return config.prompt63TimeUnit();
		else if (reminderId == 64) return config.prompt64TimeUnit();
		else if (reminderId == 65) return config.prompt65TimeUnit();
		else if (reminderId == 66) return config.prompt66TimeUnit();
		else if (reminderId == 67) return config.prompt67TimeUnit();
		else if (reminderId == 68) return config.prompt68TimeUnit();
		else if (reminderId == 69) return config.prompt69TimeUnit();
		else if (reminderId == 70) return config.prompt70TimeUnit();
		else if (reminderId == 71) return config.prompt71TimeUnit();
		else if (reminderId == 72) return config.prompt72TimeUnit();
		else if (reminderId == 73) return config.prompt73TimeUnit();
		else if (reminderId == 74) return config.prompt74TimeUnit();
		else if (reminderId == 75) return config.prompt75TimeUnit();
		else if (reminderId == 76) return config.prompt76TimeUnit();
		else if (reminderId == 77) return config.prompt77TimeUnit();
		else if (reminderId == 78) return config.prompt78TimeUnit();
		else if (reminderId == 79) return config.prompt79TimeUnit();
		else if (reminderId == 80) return config.prompt80TimeUnit();
		else if (reminderId == 81) return config.prompt81TimeUnit();
		else if (reminderId == 82) return config.prompt82TimeUnit();
		else if (reminderId == 83) return config.prompt83TimeUnit();
		else if (reminderId == 84) return config.prompt84TimeUnit();
		else if (reminderId == 85) return config.prompt85TimeUnit();
		else if (reminderId == 86) return config.prompt86TimeUnit();
		else if (reminderId == 87) return config.prompt87TimeUnit();
		else if (reminderId == 88) return config.prompt88TimeUnit();
		else if (reminderId == 89) return config.prompt89TimeUnit();
		else if (reminderId == 90) return config.prompt90TimeUnit();
		else if (reminderId == 91) return config.prompt91TimeUnit();
		else if (reminderId == 92) return config.prompt92TimeUnit();
		else if (reminderId == 93) return config.prompt93TimeUnit();
		else if (reminderId == 94) return config.prompt94TimeUnit();
		else if (reminderId == 95) return config.prompt95TimeUnit();
		else if (reminderId == 96) return config.prompt96TimeUnit();
		else if (reminderId == 97) return config.prompt97TimeUnit();
		else if (reminderId == 98) return config.prompt98TimeUnit();
		else if (reminderId == 99) return config.prompt99TimeUnit();
		else if (reminderId == 100) return config.prompt100TimeUnit();
		else return TimeUnit.SECONDS;
	}

	boolean isNotify(int reminderId)
	{
		if (reminderId == 1) return config.prompt1Notify();
		else if (reminderId == 2) return config.prompt2Notify();
		else if (reminderId == 3) return config.prompt3Notify();
		else if (reminderId == 4) return config.prompt4Notify();
		else if (reminderId == 5) return config.prompt5Notify();
		else if (reminderId == 6) return config.prompt6Notify();
		else if (reminderId == 7) return config.prompt7Notify();
		else if (reminderId == 8) return config.prompt8Notify();
		else if (reminderId == 9) return config.prompt9Notify();
		else if (reminderId == 10) return config.prompt10Notify();
		else if (reminderId == 11) return config.prompt11Notify();
		else if (reminderId == 12) return config.prompt12Notify();
		else if (reminderId == 13) return config.prompt13Notify();
		else if (reminderId == 14) return config.prompt14Notify();
		else if (reminderId == 15) return config.prompt15Notify();
		else if (reminderId == 16) return config.prompt16Notify();
		else if (reminderId == 17) return config.prompt17Notify();
		else if (reminderId == 18) return config.prompt18Notify();
		else if (reminderId == 19) return config.prompt19Notify();
		else if (reminderId == 20) return config.prompt20Notify();
		else if (reminderId == 21) return config.prompt21Notify();
		else if (reminderId == 22) return config.prompt22Notify();
		else if (reminderId == 23) return config.prompt23Notify();
		else if (reminderId == 24) return config.prompt24Notify();
		else if (reminderId == 25) return config.prompt25Notify();
		else if (reminderId == 26) return config.prompt26Notify();
		else if (reminderId == 27) return config.prompt27Notify();
		else if (reminderId == 28) return config.prompt28Notify();
		else if (reminderId == 29) return config.prompt29Notify();
		else if (reminderId == 30) return config.prompt30Notify();
		else if (reminderId == 31) return config.prompt31Notify();
		else if (reminderId == 32) return config.prompt32Notify();
		else if (reminderId == 33) return config.prompt33Notify();
		else if (reminderId == 34) return config.prompt34Notify();
		else if (reminderId == 35) return config.prompt35Notify();
		else if (reminderId == 36) return config.prompt36Notify();
		else if (reminderId == 37) return config.prompt37Notify();
		else if (reminderId == 38) return config.prompt38Notify();
		else if (reminderId == 39) return config.prompt39Notify();
		else if (reminderId == 40) return config.prompt40Notify();
		else if (reminderId == 41) return config.prompt41Notify();
		else if (reminderId == 42) return config.prompt42Notify();
		else if (reminderId == 43) return config.prompt43Notify();
		else if (reminderId == 44) return config.prompt44Notify();
		else if (reminderId == 45) return config.prompt45Notify();
		else if (reminderId == 46) return config.prompt46Notify();
		else if (reminderId == 47) return config.prompt47Notify();
		else if (reminderId == 48) return config.prompt48Notify();
		else if (reminderId == 49) return config.prompt49Notify();
		else if (reminderId == 50) return config.prompt50Notify();
		else if (reminderId == 51) return config.prompt51Notify();
		else if (reminderId == 52) return config.prompt52Notify();
		else if (reminderId == 53) return config.prompt53Notify();
		else if (reminderId == 54) return config.prompt54Notify();
		else if (reminderId == 55) return config.prompt55Notify();
		else if (reminderId == 56) return config.prompt56Notify();
		else if (reminderId == 57) return config.prompt57Notify();
		else if (reminderId == 58) return config.prompt58Notify();
		else if (reminderId == 59) return config.prompt59Notify();
		else if (reminderId == 60) return config.prompt60Notify();
		else if (reminderId == 61) return config.prompt61Notify();
		else if (reminderId == 62) return config.prompt62Notify();
		else if (reminderId == 63) return config.prompt63Notify();
		else if (reminderId == 64) return config.prompt64Notify();
		else if (reminderId == 65) return config.prompt65Notify();
		else if (reminderId == 66) return config.prompt66Notify();
		else if (reminderId == 67) return config.prompt67Notify();
		else if (reminderId == 68) return config.prompt68Notify();
		else if (reminderId == 69) return config.prompt69Notify();
		else if (reminderId == 70) return config.prompt70Notify();
		else if (reminderId == 71) return config.prompt71Notify();
		else if (reminderId == 72) return config.prompt72Notify();
		else if (reminderId == 73) return config.prompt73Notify();
		else if (reminderId == 74) return config.prompt74Notify();
		else if (reminderId == 75) return config.prompt75Notify();
		else if (reminderId == 76) return config.prompt76Notify();
		else if (reminderId == 77) return config.prompt77Notify();
		else if (reminderId == 78) return config.prompt78Notify();
		else if (reminderId == 79) return config.prompt79Notify();
		else if (reminderId == 80) return config.prompt80Notify();
		else if (reminderId == 81) return config.prompt81Notify();
		else if (reminderId == 82) return config.prompt82Notify();
		else if (reminderId == 83) return config.prompt83Notify();
		else if (reminderId == 84) return config.prompt84Notify();
		else if (reminderId == 85) return config.prompt85Notify();
		else if (reminderId == 86) return config.prompt86Notify();
		else if (reminderId == 87) return config.prompt87Notify();
		else if (reminderId == 88) return config.prompt88Notify();
		else if (reminderId == 89) return config.prompt89Notify();
		else if (reminderId == 90) return config.prompt90Notify();
		else if (reminderId == 91) return config.prompt91Notify();
		else if (reminderId == 92) return config.prompt92Notify();
		else if (reminderId == 93) return config.prompt93Notify();
		else if (reminderId == 94) return config.prompt94Notify();
		else if (reminderId == 95) return config.prompt95Notify();
		else if (reminderId == 96) return config.prompt96Notify();
		else if (reminderId == 97) return config.prompt97Notify();
		else if (reminderId == 98) return config.prompt98Notify();
		else if (reminderId == 99) return config.prompt99Notify();
		else if (reminderId == 100) return config.prompt100Notify();
		else return false;
	}

	String getTimes(int reminderId)
	{
		if (reminderId == 1) return config.prompt1Times();
		else if (reminderId == 2) return config.prompt2Times();
		else if (reminderId == 3) return config.prompt3Times();
		else if (reminderId == 4) return config.prompt4Times();
		else if (reminderId == 5) return config.prompt5Times();
		else if (reminderId == 6) return config.prompt6Times();
		else if (reminderId == 7) return config.prompt7Times();
		else if (reminderId == 8) return config.prompt8Times();
		else if (reminderId == 9) return config.prompt9Times();
		else if (reminderId == 10) return config.prompt10Times();
		else if (reminderId == 11) return config.prompt11Times();
		else if (reminderId == 12) return config.prompt12Times();
		else if (reminderId == 13) return config.prompt13Times();
		else if (reminderId == 14) return config.prompt14Times();
		else if (reminderId == 15) return config.prompt15Times();
		else if (reminderId == 16) return config.prompt16Times();
		else if (reminderId == 17) return config.prompt17Times();
		else if (reminderId == 18) return config.prompt18Times();
		else if (reminderId == 19) return config.prompt19Times();
		else if (reminderId == 20) return config.prompt20Times();
		else if (reminderId == 21) return config.prompt21Times();
		else if (reminderId == 22) return config.prompt22Times();
		else if (reminderId == 23) return config.prompt23Times();
		else if (reminderId == 24) return config.prompt24Times();
		else if (reminderId == 25) return config.prompt25Times();
		else if (reminderId == 26) return config.prompt26Times();
		else if (reminderId == 27) return config.prompt27Times();
		else if (reminderId == 28) return config.prompt28Times();
		else if (reminderId == 29) return config.prompt29Times();
		else if (reminderId == 30) return config.prompt30Times();
		else if (reminderId == 31) return config.prompt31Times();
		else if (reminderId == 32) return config.prompt32Times();
		else if (reminderId == 33) return config.prompt33Times();
		else if (reminderId == 34) return config.prompt34Times();
		else if (reminderId == 35) return config.prompt35Times();
		else if (reminderId == 36) return config.prompt36Times();
		else if (reminderId == 37) return config.prompt37Times();
		else if (reminderId == 38) return config.prompt38Times();
		else if (reminderId == 39) return config.prompt39Times();
		else if (reminderId == 40) return config.prompt40Times();
		else if (reminderId == 41) return config.prompt41Times();
		else if (reminderId == 42) return config.prompt42Times();
		else if (reminderId == 43) return config.prompt43Times();
		else if (reminderId == 44) return config.prompt44Times();
		else if (reminderId == 45) return config.prompt45Times();
		else if (reminderId == 46) return config.prompt46Times();
		else if (reminderId == 47) return config.prompt47Times();
		else if (reminderId == 48) return config.prompt48Times();
		else if (reminderId == 49) return config.prompt49Times();
		else if (reminderId == 50) return config.prompt50Times();
		else if (reminderId == 51) return config.prompt51Times();
		else if (reminderId == 52) return config.prompt52Times();
		else if (reminderId == 53) return config.prompt53Times();
		else if (reminderId == 54) return config.prompt54Times();
		else if (reminderId == 55) return config.prompt55Times();
		else if (reminderId == 56) return config.prompt56Times();
		else if (reminderId == 57) return config.prompt57Times();
		else if (reminderId == 58) return config.prompt58Times();
		else if (reminderId == 59) return config.prompt59Times();
		else if (reminderId == 60) return config.prompt60Times();
		else if (reminderId == 61) return config.prompt61Times();
		else if (reminderId == 62) return config.prompt62Times();
		else if (reminderId == 63) return config.prompt63Times();
		else if (reminderId == 64) return config.prompt64Times();
		else if (reminderId == 65) return config.prompt65Times();
		else if (reminderId == 66) return config.prompt66Times();
		else if (reminderId == 67) return config.prompt67Times();
		else if (reminderId == 68) return config.prompt68Times();
		else if (reminderId == 69) return config.prompt69Times();
		else if (reminderId == 70) return config.prompt70Times();
		else if (reminderId == 71) return config.prompt71Times();
		else if (reminderId == 72) return config.prompt72Times();
		else if (reminderId == 73) return config.prompt73Times();
		else if (reminderId == 74) return config.prompt74Times();
		else if (reminderId == 75) return config.prompt75Times();
		else if (reminderId == 76) return config.prompt76Times();
		else if (reminderId == 77) return config.prompt77Times();
		else if (reminderId == 78) return config.prompt78Times();
		else if (reminderId == 79) return config.prompt79Times();
		else if (reminderId == 80) return config.prompt80Times();
		else if (reminderId == 81) return config.prompt81Times();
		else if (reminderId == 82) return config.prompt82Times();
		else if (reminderId == 83) return config.prompt83Times();
		else if (reminderId == 84) return config.prompt84Times();
		else if (reminderId == 85) return config.prompt85Times();
		else if (reminderId == 86) return config.prompt86Times();
		else if (reminderId == 87) return config.prompt87Times();
		else if (reminderId == 88) return config.prompt88Times();
		else if (reminderId == 89) return config.prompt89Times();
		else if (reminderId == 90) return config.prompt90Times();
		else if (reminderId == 91) return config.prompt91Times();
		else if (reminderId == 92) return config.prompt92Times();
		else if (reminderId == 93) return config.prompt93Times();
		else if (reminderId == 94) return config.prompt94Times();
		else if (reminderId == 95) return config.prompt95Times();
		else if (reminderId == 96) return config.prompt96Times();
		else if (reminderId == 97) return config.prompt97Times();
		else if (reminderId == 98) return config.prompt98Times();
		else if (reminderId == 99) return config.prompt99Times();
		else if (reminderId == 100) return config.prompt100Times();
		else return "";
	}

	String getDaysOfWeek(int reminderId)
	{
		if (reminderId == 1) return config.prompt1DaysOfWeek();
		else if (reminderId == 2) return config.prompt2DaysOfWeek();
		else if (reminderId == 3) return config.prompt3DaysOfWeek();
		else if (reminderId == 4) return config.prompt4DaysOfWeek();
		else if (reminderId == 5) return config.prompt5DaysOfWeek();
		else if (reminderId == 6) return config.prompt6DaysOfWeek();
		else if (reminderId == 7) return config.prompt7DaysOfWeek();
		else if (reminderId == 8) return config.prompt8DaysOfWeek();
		else if (reminderId == 9) return config.prompt9DaysOfWeek();
		else if (reminderId == 10) return config.prompt10DaysOfWeek();
		else if (reminderId == 11) return config.prompt11DaysOfWeek();
		else if (reminderId == 12) return config.prompt12DaysOfWeek();
		else if (reminderId == 13) return config.prompt13DaysOfWeek();
		else if (reminderId == 14) return config.prompt14DaysOfWeek();
		else if (reminderId == 15) return config.prompt15DaysOfWeek();
		else if (reminderId == 16) return config.prompt16DaysOfWeek();
		else if (reminderId == 17) return config.prompt17DaysOfWeek();
		else if (reminderId == 18) return config.prompt18DaysOfWeek();
		else if (reminderId == 19) return config.prompt19DaysOfWeek();
		else if (reminderId == 20) return config.prompt20DaysOfWeek();
		else if (reminderId == 21) return config.prompt21DaysOfWeek();
		else if (reminderId == 22) return config.prompt22DaysOfWeek();
		else if (reminderId == 23) return config.prompt23DaysOfWeek();
		else if (reminderId == 24) return config.prompt24DaysOfWeek();
		else if (reminderId == 25) return config.prompt25DaysOfWeek();
		else if (reminderId == 26) return config.prompt26DaysOfWeek();
		else if (reminderId == 27) return config.prompt27DaysOfWeek();
		else if (reminderId == 28) return config.prompt28DaysOfWeek();
		else if (reminderId == 29) return config.prompt29DaysOfWeek();
		else if (reminderId == 30) return config.prompt30DaysOfWeek();
		else if (reminderId == 31) return config.prompt31DaysOfWeek();
		else if (reminderId == 32) return config.prompt32DaysOfWeek();
		else if (reminderId == 33) return config.prompt33DaysOfWeek();
		else if (reminderId == 34) return config.prompt34DaysOfWeek();
		else if (reminderId == 35) return config.prompt35DaysOfWeek();
		else if (reminderId == 36) return config.prompt36DaysOfWeek();
		else if (reminderId == 37) return config.prompt37DaysOfWeek();
		else if (reminderId == 38) return config.prompt38DaysOfWeek();
		else if (reminderId == 39) return config.prompt39DaysOfWeek();
		else if (reminderId == 40) return config.prompt40DaysOfWeek();
		else if (reminderId == 41) return config.prompt41DaysOfWeek();
		else if (reminderId == 42) return config.prompt42DaysOfWeek();
		else if (reminderId == 43) return config.prompt43DaysOfWeek();
		else if (reminderId == 44) return config.prompt44DaysOfWeek();
		else if (reminderId == 45) return config.prompt45DaysOfWeek();
		else if (reminderId == 46) return config.prompt46DaysOfWeek();
		else if (reminderId == 47) return config.prompt47DaysOfWeek();
		else if (reminderId == 48) return config.prompt48DaysOfWeek();
		else if (reminderId == 49) return config.prompt49DaysOfWeek();
		else if (reminderId == 50) return config.prompt50DaysOfWeek();
		else if (reminderId == 51) return config.prompt51DaysOfWeek();
		else if (reminderId == 52) return config.prompt52DaysOfWeek();
		else if (reminderId == 53) return config.prompt53DaysOfWeek();
		else if (reminderId == 54) return config.prompt54DaysOfWeek();
		else if (reminderId == 55) return config.prompt55DaysOfWeek();
		else if (reminderId == 56) return config.prompt56DaysOfWeek();
		else if (reminderId == 57) return config.prompt57DaysOfWeek();
		else if (reminderId == 58) return config.prompt58DaysOfWeek();
		else if (reminderId == 59) return config.prompt59DaysOfWeek();
		else if (reminderId == 60) return config.prompt60DaysOfWeek();
		else if (reminderId == 61) return config.prompt61DaysOfWeek();
		else if (reminderId == 62) return config.prompt62DaysOfWeek();
		else if (reminderId == 63) return config.prompt63DaysOfWeek();
		else if (reminderId == 64) return config.prompt64DaysOfWeek();
		else if (reminderId == 65) return config.prompt65DaysOfWeek();
		else if (reminderId == 66) return config.prompt66DaysOfWeek();
		else if (reminderId == 67) return config.prompt67DaysOfWeek();
		else if (reminderId == 68) return config.prompt68DaysOfWeek();
		else if (reminderId == 69) return config.prompt69DaysOfWeek();
		else if (reminderId == 70) return config.prompt70DaysOfWeek();
		else if (reminderId == 71) return config.prompt71DaysOfWeek();
		else if (reminderId == 72) return config.prompt72DaysOfWeek();
		else if (reminderId == 73) return config.prompt73DaysOfWeek();
		else if (reminderId == 74) return config.prompt74DaysOfWeek();
		else if (reminderId == 75) return config.prompt75DaysOfWeek();
		else if (reminderId == 76) return config.prompt76DaysOfWeek();
		else if (reminderId == 77) return config.prompt77DaysOfWeek();
		else if (reminderId == 78) return config.prompt78DaysOfWeek();
		else if (reminderId == 79) return config.prompt79DaysOfWeek();
		else if (reminderId == 80) return config.prompt80DaysOfWeek();
		else if (reminderId == 81) return config.prompt81DaysOfWeek();
		else if (reminderId == 82) return config.prompt82DaysOfWeek();
		else if (reminderId == 83) return config.prompt83DaysOfWeek();
		else if (reminderId == 84) return config.prompt84DaysOfWeek();
		else if (reminderId == 85) return config.prompt85DaysOfWeek();
		else if (reminderId == 86) return config.prompt86DaysOfWeek();
		else if (reminderId == 87) return config.prompt87DaysOfWeek();
		else if (reminderId == 88) return config.prompt88DaysOfWeek();
		else if (reminderId == 89) return config.prompt89DaysOfWeek();
		else if (reminderId == 90) return config.prompt90DaysOfWeek();
		else if (reminderId == 91) return config.prompt91DaysOfWeek();
		else if (reminderId == 92) return config.prompt92DaysOfWeek();
		else if (reminderId == 93) return config.prompt93DaysOfWeek();
		else if (reminderId == 94) return config.prompt94DaysOfWeek();
		else if (reminderId == 95) return config.prompt95DaysOfWeek();
		else if (reminderId == 96) return config.prompt96DaysOfWeek();
		else if (reminderId == 97) return config.prompt97DaysOfWeek();
		else if (reminderId == 98) return config.prompt98DaysOfWeek();
		else if (reminderId == 99) return config.prompt99DaysOfWeek();
		else if (reminderId == 100) return config.prompt100DaysOfWeek();
		else return "";
	}

	String getDates(int reminderId)
	{
		if (reminderId == 1) return config.prompt1Dates();
		else if (reminderId == 2) return config.prompt2Dates();
		else if (reminderId == 3) return config.prompt3Dates();
		else if (reminderId == 4) return config.prompt4Dates();
		else if (reminderId == 5) return config.prompt5Dates();
		else if (reminderId == 6) return config.prompt6Dates();
		else if (reminderId == 7) return config.prompt7Dates();
		else if (reminderId == 8) return config.prompt8Dates();
		else if (reminderId == 9) return config.prompt9Dates();
		else if (reminderId == 10) return config.prompt10Dates();
		else if (reminderId == 11) return config.prompt11Dates();
		else if (reminderId == 12) return config.prompt12Dates();
		else if (reminderId == 13) return config.prompt13Dates();
		else if (reminderId == 14) return config.prompt14Dates();
		else if (reminderId == 15) return config.prompt15Dates();
		else if (reminderId == 16) return config.prompt16Dates();
		else if (reminderId == 17) return config.prompt17Dates();
		else if (reminderId == 18) return config.prompt18Dates();
		else if (reminderId == 19) return config.prompt19Dates();
		else if (reminderId == 20) return config.prompt20Dates();
		else if (reminderId == 21) return config.prompt21Dates();
		else if (reminderId == 22) return config.prompt22Dates();
		else if (reminderId == 23) return config.prompt23Dates();
		else if (reminderId == 24) return config.prompt24Dates();
		else if (reminderId == 25) return config.prompt25Dates();
		else if (reminderId == 26) return config.prompt26Dates();
		else if (reminderId == 27) return config.prompt27Dates();
		else if (reminderId == 28) return config.prompt28Dates();
		else if (reminderId == 29) return config.prompt29Dates();
		else if (reminderId == 30) return config.prompt30Dates();
		else if (reminderId == 31) return config.prompt31Dates();
		else if (reminderId == 32) return config.prompt32Dates();
		else if (reminderId == 33) return config.prompt33Dates();
		else if (reminderId == 34) return config.prompt34Dates();
		else if (reminderId == 35) return config.prompt35Dates();
		else if (reminderId == 36) return config.prompt36Dates();
		else if (reminderId == 37) return config.prompt37Dates();
		else if (reminderId == 38) return config.prompt38Dates();
		else if (reminderId == 39) return config.prompt39Dates();
		else if (reminderId == 40) return config.prompt40Dates();
		else if (reminderId == 41) return config.prompt41Dates();
		else if (reminderId == 42) return config.prompt42Dates();
		else if (reminderId == 43) return config.prompt43Dates();
		else if (reminderId == 44) return config.prompt44Dates();
		else if (reminderId == 45) return config.prompt45Dates();
		else if (reminderId == 46) return config.prompt46Dates();
		else if (reminderId == 47) return config.prompt47Dates();
		else if (reminderId == 48) return config.prompt48Dates();
		else if (reminderId == 49) return config.prompt49Dates();
		else if (reminderId == 50) return config.prompt50Dates();
		else if (reminderId == 51) return config.prompt51Dates();
		else if (reminderId == 52) return config.prompt52Dates();
		else if (reminderId == 53) return config.prompt53Dates();
		else if (reminderId == 54) return config.prompt54Dates();
		else if (reminderId == 55) return config.prompt55Dates();
		else if (reminderId == 56) return config.prompt56Dates();
		else if (reminderId == 57) return config.prompt57Dates();
		else if (reminderId == 58) return config.prompt58Dates();
		else if (reminderId == 59) return config.prompt59Dates();
		else if (reminderId == 60) return config.prompt60Dates();
		else if (reminderId == 61) return config.prompt61Dates();
		else if (reminderId == 62) return config.prompt62Dates();
		else if (reminderId == 63) return config.prompt63Dates();
		else if (reminderId == 64) return config.prompt64Dates();
		else if (reminderId == 65) return config.prompt65Dates();
		else if (reminderId == 66) return config.prompt66Dates();
		else if (reminderId == 67) return config.prompt67Dates();
		else if (reminderId == 68) return config.prompt68Dates();
		else if (reminderId == 69) return config.prompt69Dates();
		else if (reminderId == 70) return config.prompt70Dates();
		else if (reminderId == 71) return config.prompt71Dates();
		else if (reminderId == 72) return config.prompt72Dates();
		else if (reminderId == 73) return config.prompt73Dates();
		else if (reminderId == 74) return config.prompt74Dates();
		else if (reminderId == 75) return config.prompt75Dates();
		else if (reminderId == 76) return config.prompt76Dates();
		else if (reminderId == 77) return config.prompt77Dates();
		else if (reminderId == 78) return config.prompt78Dates();
		else if (reminderId == 79) return config.prompt79Dates();
		else if (reminderId == 80) return config.prompt80Dates();
		else if (reminderId == 81) return config.prompt81Dates();
		else if (reminderId == 82) return config.prompt82Dates();
		else if (reminderId == 83) return config.prompt83Dates();
		else if (reminderId == 84) return config.prompt84Dates();
		else if (reminderId == 85) return config.prompt85Dates();
		else if (reminderId == 86) return config.prompt86Dates();
		else if (reminderId == 87) return config.prompt87Dates();
		else if (reminderId == 88) return config.prompt88Dates();
		else if (reminderId == 89) return config.prompt89Dates();
		else if (reminderId == 90) return config.prompt90Dates();
		else if (reminderId == 91) return config.prompt91Dates();
		else if (reminderId == 92) return config.prompt92Dates();
		else if (reminderId == 93) return config.prompt93Dates();
		else if (reminderId == 94) return config.prompt94Dates();
		else if (reminderId == 95) return config.prompt95Dates();
		else if (reminderId == 96) return config.prompt96Dates();
		else if (reminderId == 97) return config.prompt97Dates();
		else if (reminderId == 98) return config.prompt98Dates();
		else if (reminderId == 99) return config.prompt99Dates();
		else if (reminderId == 100) return config.prompt100Dates();
		else return "";
	}

	String getCoordinates(int reminderId)
	{
		if (reminderId == 1) return config.prompt1Coordinates();
		else if (reminderId == 2) return config.prompt2Coordinates();
		else if (reminderId == 3) return config.prompt3Coordinates();
		else if (reminderId == 4) return config.prompt4Coordinates();
		else if (reminderId == 5) return config.prompt5Coordinates();
		else if (reminderId == 6) return config.prompt6Coordinates();
		else if (reminderId == 7) return config.prompt7Coordinates();
		else if (reminderId == 8) return config.prompt8Coordinates();
		else if (reminderId == 9) return config.prompt9Coordinates();
		else if (reminderId == 10) return config.prompt10Coordinates();
		else if (reminderId == 11) return config.prompt11Coordinates();
		else if (reminderId == 12) return config.prompt12Coordinates();
		else if (reminderId == 13) return config.prompt13Coordinates();
		else if (reminderId == 14) return config.prompt14Coordinates();
		else if (reminderId == 15) return config.prompt15Coordinates();
		else if (reminderId == 16) return config.prompt16Coordinates();
		else if (reminderId == 17) return config.prompt17Coordinates();
		else if (reminderId == 18) return config.prompt18Coordinates();
		else if (reminderId == 19) return config.prompt19Coordinates();
		else if (reminderId == 20) return config.prompt20Coordinates();
		else if (reminderId == 21) return config.prompt21Coordinates();
		else if (reminderId == 22) return config.prompt22Coordinates();
		else if (reminderId == 23) return config.prompt23Coordinates();
		else if (reminderId == 24) return config.prompt24Coordinates();
		else if (reminderId == 25) return config.prompt25Coordinates();
		else if (reminderId == 26) return config.prompt26Coordinates();
		else if (reminderId == 27) return config.prompt27Coordinates();
		else if (reminderId == 28) return config.prompt28Coordinates();
		else if (reminderId == 29) return config.prompt29Coordinates();
		else if (reminderId == 30) return config.prompt30Coordinates();
		else if (reminderId == 31) return config.prompt31Coordinates();
		else if (reminderId == 32) return config.prompt32Coordinates();
		else if (reminderId == 33) return config.prompt33Coordinates();
		else if (reminderId == 34) return config.prompt34Coordinates();
		else if (reminderId == 35) return config.prompt35Coordinates();
		else if (reminderId == 36) return config.prompt36Coordinates();
		else if (reminderId == 37) return config.prompt37Coordinates();
		else if (reminderId == 38) return config.prompt38Coordinates();
		else if (reminderId == 39) return config.prompt39Coordinates();
		else if (reminderId == 40) return config.prompt40Coordinates();
		else if (reminderId == 41) return config.prompt41Coordinates();
		else if (reminderId == 42) return config.prompt42Coordinates();
		else if (reminderId == 43) return config.prompt43Coordinates();
		else if (reminderId == 44) return config.prompt44Coordinates();
		else if (reminderId == 45) return config.prompt45Coordinates();
		else if (reminderId == 46) return config.prompt46Coordinates();
		else if (reminderId == 47) return config.prompt47Coordinates();
		else if (reminderId == 48) return config.prompt48Coordinates();
		else if (reminderId == 49) return config.prompt49Coordinates();
		else if (reminderId == 50) return config.prompt50Coordinates();
		else if (reminderId == 51) return config.prompt51Coordinates();
		else if (reminderId == 52) return config.prompt52Coordinates();
		else if (reminderId == 53) return config.prompt53Coordinates();
		else if (reminderId == 54) return config.prompt54Coordinates();
		else if (reminderId == 55) return config.prompt55Coordinates();
		else if (reminderId == 56) return config.prompt56Coordinates();
		else if (reminderId == 57) return config.prompt57Coordinates();
		else if (reminderId == 58) return config.prompt58Coordinates();
		else if (reminderId == 59) return config.prompt59Coordinates();
		else if (reminderId == 60) return config.prompt60Coordinates();
		else if (reminderId == 61) return config.prompt61Coordinates();
		else if (reminderId == 62) return config.prompt62Coordinates();
		else if (reminderId == 63) return config.prompt63Coordinates();
		else if (reminderId == 64) return config.prompt64Coordinates();
		else if (reminderId == 65) return config.prompt65Coordinates();
		else if (reminderId == 66) return config.prompt66Coordinates();
		else if (reminderId == 67) return config.prompt67Coordinates();
		else if (reminderId == 68) return config.prompt68Coordinates();
		else if (reminderId == 69) return config.prompt69Coordinates();
		else if (reminderId == 70) return config.prompt70Coordinates();
		else if (reminderId == 71) return config.prompt71Coordinates();
		else if (reminderId == 72) return config.prompt72Coordinates();
		else if (reminderId == 73) return config.prompt73Coordinates();
		else if (reminderId == 74) return config.prompt74Coordinates();
		else if (reminderId == 75) return config.prompt75Coordinates();
		else if (reminderId == 76) return config.prompt76Coordinates();
		else if (reminderId == 77) return config.prompt77Coordinates();
		else if (reminderId == 78) return config.prompt78Coordinates();
		else if (reminderId == 79) return config.prompt79Coordinates();
		else if (reminderId == 80) return config.prompt80Coordinates();
		else if (reminderId == 81) return config.prompt81Coordinates();
		else if (reminderId == 82) return config.prompt82Coordinates();
		else if (reminderId == 83) return config.prompt83Coordinates();
		else if (reminderId == 84) return config.prompt84Coordinates();
		else if (reminderId == 85) return config.prompt85Coordinates();
		else if (reminderId == 86) return config.prompt86Coordinates();
		else if (reminderId == 87) return config.prompt87Coordinates();
		else if (reminderId == 88) return config.prompt88Coordinates();
		else if (reminderId == 89) return config.prompt89Coordinates();
		else if (reminderId == 90) return config.prompt90Coordinates();
		else if (reminderId == 91) return config.prompt91Coordinates();
		else if (reminderId == 92) return config.prompt92Coordinates();
		else if (reminderId == 93) return config.prompt93Coordinates();
		else if (reminderId == 94) return config.prompt94Coordinates();
		else if (reminderId == 95) return config.prompt95Coordinates();
		else if (reminderId == 96) return config.prompt96Coordinates();
		else if (reminderId == 97) return config.prompt97Coordinates();
		else if (reminderId == 98) return config.prompt98Coordinates();
		else if (reminderId == 99) return config.prompt99Coordinates();
		else if (reminderId == 100) return config.prompt100Coordinates();
		else return "";
	}

	int getRadius(int reminderId)
	{
		if (reminderId == 1) return config.prompt1Radius();
		else if (reminderId == 2) return config.prompt2Radius();
		else if (reminderId == 3) return config.prompt3Radius();
		else if (reminderId == 4) return config.prompt4Radius();
		else if (reminderId == 5) return config.prompt5Radius();
		else if (reminderId == 6) return config.prompt6Radius();
		else if (reminderId == 7) return config.prompt7Radius();
		else if (reminderId == 8) return config.prompt8Radius();
		else if (reminderId == 9) return config.prompt9Radius();
		else if (reminderId == 10) return config.prompt10Radius();
		else if (reminderId == 11) return config.prompt11Radius();
		else if (reminderId == 12) return config.prompt12Radius();
		else if (reminderId == 13) return config.prompt13Radius();
		else if (reminderId == 14) return config.prompt14Radius();
		else if (reminderId == 15) return config.prompt15Radius();
		else if (reminderId == 16) return config.prompt16Radius();
		else if (reminderId == 17) return config.prompt17Radius();
		else if (reminderId == 18) return config.prompt18Radius();
		else if (reminderId == 19) return config.prompt19Radius();
		else if (reminderId == 20) return config.prompt20Radius();
		else if (reminderId == 21) return config.prompt21Radius();
		else if (reminderId == 22) return config.prompt22Radius();
		else if (reminderId == 23) return config.prompt23Radius();
		else if (reminderId == 24) return config.prompt24Radius();
		else if (reminderId == 25) return config.prompt25Radius();
		else if (reminderId == 26) return config.prompt26Radius();
		else if (reminderId == 27) return config.prompt27Radius();
		else if (reminderId == 28) return config.prompt28Radius();
		else if (reminderId == 29) return config.prompt29Radius();
		else if (reminderId == 30) return config.prompt30Radius();
		else if (reminderId == 31) return config.prompt31Radius();
		else if (reminderId == 32) return config.prompt32Radius();
		else if (reminderId == 33) return config.prompt33Radius();
		else if (reminderId == 34) return config.prompt34Radius();
		else if (reminderId == 35) return config.prompt35Radius();
		else if (reminderId == 36) return config.prompt36Radius();
		else if (reminderId == 37) return config.prompt37Radius();
		else if (reminderId == 38) return config.prompt38Radius();
		else if (reminderId == 39) return config.prompt39Radius();
		else if (reminderId == 40) return config.prompt40Radius();
		else if (reminderId == 41) return config.prompt41Radius();
		else if (reminderId == 42) return config.prompt42Radius();
		else if (reminderId == 43) return config.prompt43Radius();
		else if (reminderId == 44) return config.prompt44Radius();
		else if (reminderId == 45) return config.prompt45Radius();
		else if (reminderId == 46) return config.prompt46Radius();
		else if (reminderId == 47) return config.prompt47Radius();
		else if (reminderId == 48) return config.prompt48Radius();
		else if (reminderId == 49) return config.prompt49Radius();
		else if (reminderId == 50) return config.prompt50Radius();
		else if (reminderId == 51) return config.prompt51Radius();
		else if (reminderId == 52) return config.prompt52Radius();
		else if (reminderId == 53) return config.prompt53Radius();
		else if (reminderId == 54) return config.prompt54Radius();
		else if (reminderId == 55) return config.prompt55Radius();
		else if (reminderId == 56) return config.prompt56Radius();
		else if (reminderId == 57) return config.prompt57Radius();
		else if (reminderId == 58) return config.prompt58Radius();
		else if (reminderId == 59) return config.prompt59Radius();
		else if (reminderId == 60) return config.prompt60Radius();
		else if (reminderId == 61) return config.prompt61Radius();
		else if (reminderId == 62) return config.prompt62Radius();
		else if (reminderId == 63) return config.prompt63Radius();
		else if (reminderId == 64) return config.prompt64Radius();
		else if (reminderId == 65) return config.prompt65Radius();
		else if (reminderId == 66) return config.prompt66Radius();
		else if (reminderId == 67) return config.prompt67Radius();
		else if (reminderId == 68) return config.prompt68Radius();
		else if (reminderId == 69) return config.prompt69Radius();
		else if (reminderId == 70) return config.prompt70Radius();
		else if (reminderId == 71) return config.prompt71Radius();
		else if (reminderId == 72) return config.prompt72Radius();
		else if (reminderId == 73) return config.prompt73Radius();
		else if (reminderId == 74) return config.prompt74Radius();
		else if (reminderId == 75) return config.prompt75Radius();
		else if (reminderId == 76) return config.prompt76Radius();
		else if (reminderId == 77) return config.prompt77Radius();
		else if (reminderId == 78) return config.prompt78Radius();
		else if (reminderId == 79) return config.prompt79Radius();
		else if (reminderId == 80) return config.prompt80Radius();
		else if (reminderId == 81) return config.prompt81Radius();
		else if (reminderId == 82) return config.prompt82Radius();
		else if (reminderId == 83) return config.prompt83Radius();
		else if (reminderId == 84) return config.prompt84Radius();
		else if (reminderId == 85) return config.prompt85Radius();
		else if (reminderId == 86) return config.prompt86Radius();
		else if (reminderId == 87) return config.prompt87Radius();
		else if (reminderId == 88) return config.prompt88Radius();
		else if (reminderId == 89) return config.prompt89Radius();
		else if (reminderId == 90) return config.prompt90Radius();
		else if (reminderId == 91) return config.prompt91Radius();
		else if (reminderId == 92) return config.prompt92Radius();
		else if (reminderId == 93) return config.prompt93Radius();
		else if (reminderId == 94) return config.prompt94Radius();
		else if (reminderId == 95) return config.prompt95Radius();
		else if (reminderId == 96) return config.prompt96Radius();
		else if (reminderId == 97) return config.prompt97Radius();
		else if (reminderId == 98) return config.prompt98Radius();
		else if (reminderId == 99) return config.prompt99Radius();
		else if (reminderId == 100) return config.prompt100Radius();
		else return 0;
	}

	String getGeofences(int reminderId)
	{
		if (reminderId == 1) return config.prompt1Geofences();
		else if (reminderId == 2) return config.prompt2Geofences();
		else if (reminderId == 3) return config.prompt3Geofences();
		else if (reminderId == 4) return config.prompt4Geofences();
		else if (reminderId == 5) return config.prompt5Geofences();
		else if (reminderId == 6) return config.prompt6Geofences();
		else if (reminderId == 7) return config.prompt7Geofences();
		else if (reminderId == 8) return config.prompt8Geofences();
		else if (reminderId == 9) return config.prompt9Geofences();
		else if (reminderId == 10) return config.prompt10Geofences();
		else if (reminderId == 11) return config.prompt11Geofences();
		else if (reminderId == 12) return config.prompt12Geofences();
		else if (reminderId == 13) return config.prompt13Geofences();
		else if (reminderId == 14) return config.prompt14Geofences();
		else if (reminderId == 15) return config.prompt15Geofences();
		else if (reminderId == 16) return config.prompt16Geofences();
		else if (reminderId == 17) return config.prompt17Geofences();
		else if (reminderId == 18) return config.prompt18Geofences();
		else if (reminderId == 19) return config.prompt19Geofences();
		else if (reminderId == 20) return config.prompt20Geofences();
		else if (reminderId == 21) return config.prompt21Geofences();
		else if (reminderId == 22) return config.prompt22Geofences();
		else if (reminderId == 23) return config.prompt23Geofences();
		else if (reminderId == 24) return config.prompt24Geofences();
		else if (reminderId == 25) return config.prompt25Geofences();
		else if (reminderId == 26) return config.prompt26Geofences();
		else if (reminderId == 27) return config.prompt27Geofences();
		else if (reminderId == 28) return config.prompt28Geofences();
		else if (reminderId == 29) return config.prompt29Geofences();
		else if (reminderId == 30) return config.prompt30Geofences();
		else if (reminderId == 31) return config.prompt31Geofences();
		else if (reminderId == 32) return config.prompt32Geofences();
		else if (reminderId == 33) return config.prompt33Geofences();
		else if (reminderId == 34) return config.prompt34Geofences();
		else if (reminderId == 35) return config.prompt35Geofences();
		else if (reminderId == 36) return config.prompt36Geofences();
		else if (reminderId == 37) return config.prompt37Geofences();
		else if (reminderId == 38) return config.prompt38Geofences();
		else if (reminderId == 39) return config.prompt39Geofences();
		else if (reminderId == 40) return config.prompt40Geofences();
		else if (reminderId == 41) return config.prompt41Geofences();
		else if (reminderId == 42) return config.prompt42Geofences();
		else if (reminderId == 43) return config.prompt43Geofences();
		else if (reminderId == 44) return config.prompt44Geofences();
		else if (reminderId == 45) return config.prompt45Geofences();
		else if (reminderId == 46) return config.prompt46Geofences();
		else if (reminderId == 47) return config.prompt47Geofences();
		else if (reminderId == 48) return config.prompt48Geofences();
		else if (reminderId == 49) return config.prompt49Geofences();
		else if (reminderId == 50) return config.prompt50Geofences();
		else if (reminderId == 51) return config.prompt51Geofences();
		else if (reminderId == 52) return config.prompt52Geofences();
		else if (reminderId == 53) return config.prompt53Geofences();
		else if (reminderId == 54) return config.prompt54Geofences();
		else if (reminderId == 55) return config.prompt55Geofences();
		else if (reminderId == 56) return config.prompt56Geofences();
		else if (reminderId == 57) return config.prompt57Geofences();
		else if (reminderId == 58) return config.prompt58Geofences();
		else if (reminderId == 59) return config.prompt59Geofences();
		else if (reminderId == 60) return config.prompt60Geofences();
		else if (reminderId == 61) return config.prompt61Geofences();
		else if (reminderId == 62) return config.prompt62Geofences();
		else if (reminderId == 63) return config.prompt63Geofences();
		else if (reminderId == 64) return config.prompt64Geofences();
		else if (reminderId == 65) return config.prompt65Geofences();
		else if (reminderId == 66) return config.prompt66Geofences();
		else if (reminderId == 67) return config.prompt67Geofences();
		else if (reminderId == 68) return config.prompt68Geofences();
		else if (reminderId == 69) return config.prompt69Geofences();
		else if (reminderId == 70) return config.prompt70Geofences();
		else if (reminderId == 71) return config.prompt71Geofences();
		else if (reminderId == 72) return config.prompt72Geofences();
		else if (reminderId == 73) return config.prompt73Geofences();
		else if (reminderId == 74) return config.prompt74Geofences();
		else if (reminderId == 75) return config.prompt75Geofences();
		else if (reminderId == 76) return config.prompt76Geofences();
		else if (reminderId == 77) return config.prompt77Geofences();
		else if (reminderId == 78) return config.prompt78Geofences();
		else if (reminderId == 79) return config.prompt79Geofences();
		else if (reminderId == 80) return config.prompt80Geofences();
		else if (reminderId == 81) return config.prompt81Geofences();
		else if (reminderId == 82) return config.prompt82Geofences();
		else if (reminderId == 83) return config.prompt83Geofences();
		else if (reminderId == 84) return config.prompt84Geofences();
		else if (reminderId == 85) return config.prompt85Geofences();
		else if (reminderId == 86) return config.prompt86Geofences();
		else if (reminderId == 87) return config.prompt87Geofences();
		else if (reminderId == 88) return config.prompt88Geofences();
		else if (reminderId == 89) return config.prompt89Geofences();
		else if (reminderId == 90) return config.prompt90Geofences();
		else if (reminderId == 91) return config.prompt91Geofences();
		else if (reminderId == 92) return config.prompt92Geofences();
		else if (reminderId == 93) return config.prompt93Geofences();
		else if (reminderId == 94) return config.prompt94Geofences();
		else if (reminderId == 95) return config.prompt95Geofences();
		else if (reminderId == 96) return config.prompt96Geofences();
		else if (reminderId == 97) return config.prompt97Geofences();
		else if (reminderId == 98) return config.prompt98Geofences();
		else if (reminderId == 99) return config.prompt99Geofences();
		else if (reminderId == 100) return config.prompt100Geofences();
		else return "";
	}

	String getRegionIds(int reminderId)
	{
		if (reminderId == 1) return config.prompt1RegionIds();
		else if (reminderId == 2) return config.prompt2RegionIds();
		else if (reminderId == 3) return config.prompt3RegionIds();
		else if (reminderId == 4) return config.prompt4RegionIds();
		else if (reminderId == 5) return config.prompt5RegionIds();
		else if (reminderId == 6) return config.prompt6RegionIds();
		else if (reminderId == 7) return config.prompt7RegionIds();
		else if (reminderId == 8) return config.prompt8RegionIds();
		else if (reminderId == 9) return config.prompt9RegionIds();
		else if (reminderId == 10) return config.prompt10RegionIds();
		else if (reminderId == 11) return config.prompt11RegionIds();
		else if (reminderId == 12) return config.prompt12RegionIds();
		else if (reminderId == 13) return config.prompt13RegionIds();
		else if (reminderId == 14) return config.prompt14RegionIds();
		else if (reminderId == 15) return config.prompt15RegionIds();
		else if (reminderId == 16) return config.prompt16RegionIds();
		else if (reminderId == 17) return config.prompt17RegionIds();
		else if (reminderId == 18) return config.prompt18RegionIds();
		else if (reminderId == 19) return config.prompt19RegionIds();
		else if (reminderId == 20) return config.prompt20RegionIds();
		else if (reminderId == 21) return config.prompt21RegionIds();
		else if (reminderId == 22) return config.prompt22RegionIds();
		else if (reminderId == 23) return config.prompt23RegionIds();
		else if (reminderId == 24) return config.prompt24RegionIds();
		else if (reminderId == 25) return config.prompt25RegionIds();
		else if (reminderId == 26) return config.prompt26RegionIds();
		else if (reminderId == 27) return config.prompt27RegionIds();
		else if (reminderId == 28) return config.prompt28RegionIds();
		else if (reminderId == 29) return config.prompt29RegionIds();
		else if (reminderId == 30) return config.prompt30RegionIds();
		else if (reminderId == 31) return config.prompt31RegionIds();
		else if (reminderId == 32) return config.prompt32RegionIds();
		else if (reminderId == 33) return config.prompt33RegionIds();
		else if (reminderId == 34) return config.prompt34RegionIds();
		else if (reminderId == 35) return config.prompt35RegionIds();
		else if (reminderId == 36) return config.prompt36RegionIds();
		else if (reminderId == 37) return config.prompt37RegionIds();
		else if (reminderId == 38) return config.prompt38RegionIds();
		else if (reminderId == 39) return config.prompt39RegionIds();
		else if (reminderId == 40) return config.prompt40RegionIds();
		else if (reminderId == 41) return config.prompt41RegionIds();
		else if (reminderId == 42) return config.prompt42RegionIds();
		else if (reminderId == 43) return config.prompt43RegionIds();
		else if (reminderId == 44) return config.prompt44RegionIds();
		else if (reminderId == 45) return config.prompt45RegionIds();
		else if (reminderId == 46) return config.prompt46RegionIds();
		else if (reminderId == 47) return config.prompt47RegionIds();
		else if (reminderId == 48) return config.prompt48RegionIds();
		else if (reminderId == 49) return config.prompt49RegionIds();
		else if (reminderId == 50) return config.prompt50RegionIds();
		else if (reminderId == 51) return config.prompt51RegionIds();
		else if (reminderId == 52) return config.prompt52RegionIds();
		else if (reminderId == 53) return config.prompt53RegionIds();
		else if (reminderId == 54) return config.prompt54RegionIds();
		else if (reminderId == 55) return config.prompt55RegionIds();
		else if (reminderId == 56) return config.prompt56RegionIds();
		else if (reminderId == 57) return config.prompt57RegionIds();
		else if (reminderId == 58) return config.prompt58RegionIds();
		else if (reminderId == 59) return config.prompt59RegionIds();
		else if (reminderId == 60) return config.prompt60RegionIds();
		else if (reminderId == 61) return config.prompt61RegionIds();
		else if (reminderId == 62) return config.prompt62RegionIds();
		else if (reminderId == 63) return config.prompt63RegionIds();
		else if (reminderId == 64) return config.prompt64RegionIds();
		else if (reminderId == 65) return config.prompt65RegionIds();
		else if (reminderId == 66) return config.prompt66RegionIds();
		else if (reminderId == 67) return config.prompt67RegionIds();
		else if (reminderId == 68) return config.prompt68RegionIds();
		else if (reminderId == 69) return config.prompt69RegionIds();
		else if (reminderId == 70) return config.prompt70RegionIds();
		else if (reminderId == 71) return config.prompt71RegionIds();
		else if (reminderId == 72) return config.prompt72RegionIds();
		else if (reminderId == 73) return config.prompt73RegionIds();
		else if (reminderId == 74) return config.prompt74RegionIds();
		else if (reminderId == 75) return config.prompt75RegionIds();
		else if (reminderId == 76) return config.prompt76RegionIds();
		else if (reminderId == 77) return config.prompt77RegionIds();
		else if (reminderId == 78) return config.prompt78RegionIds();
		else if (reminderId == 79) return config.prompt79RegionIds();
		else if (reminderId == 80) return config.prompt80RegionIds();
		else if (reminderId == 81) return config.prompt81RegionIds();
		else if (reminderId == 82) return config.prompt82RegionIds();
		else if (reminderId == 83) return config.prompt83RegionIds();
		else if (reminderId == 84) return config.prompt84RegionIds();
		else if (reminderId == 85) return config.prompt85RegionIds();
		else if (reminderId == 86) return config.prompt86RegionIds();
		else if (reminderId == 87) return config.prompt87RegionIds();
		else if (reminderId == 88) return config.prompt88RegionIds();
		else if (reminderId == 89) return config.prompt89RegionIds();
		else if (reminderId == 90) return config.prompt90RegionIds();
		else if (reminderId == 91) return config.prompt91RegionIds();
		else if (reminderId == 92) return config.prompt92RegionIds();
		else if (reminderId == 93) return config.prompt93RegionIds();
		else if (reminderId == 94) return config.prompt94RegionIds();
		else if (reminderId == 95) return config.prompt95RegionIds();
		else if (reminderId == 96) return config.prompt96RegionIds();
		else if (reminderId == 97) return config.prompt97RegionIds();
		else if (reminderId == 98) return config.prompt98RegionIds();
		else if (reminderId == 99) return config.prompt99RegionIds();
		else if (reminderId == 100) return config.prompt100RegionIds();
		else return "";
	}

	String getNpcIds(int reminderId)
	{
		if (reminderId == 1) return config.prompt1NpcIds();
		else if (reminderId == 2) return config.prompt2NpcIds();
		else if (reminderId == 3) return config.prompt3NpcIds();
		else if (reminderId == 4) return config.prompt4NpcIds();
		else if (reminderId == 5) return config.prompt5NpcIds();
		else if (reminderId == 6) return config.prompt6NpcIds();
		else if (reminderId == 7) return config.prompt7NpcIds();
		else if (reminderId == 8) return config.prompt8NpcIds();
		else if (reminderId == 9) return config.prompt9NpcIds();
		else if (reminderId == 10) return config.prompt10NpcIds();
		else if (reminderId == 11) return config.prompt11NpcIds();
		else if (reminderId == 12) return config.prompt12NpcIds();
		else if (reminderId == 13) return config.prompt13NpcIds();
		else if (reminderId == 14) return config.prompt14NpcIds();
		else if (reminderId == 15) return config.prompt15NpcIds();
		else if (reminderId == 16) return config.prompt16NpcIds();
		else if (reminderId == 17) return config.prompt17NpcIds();
		else if (reminderId == 18) return config.prompt18NpcIds();
		else if (reminderId == 19) return config.prompt19NpcIds();
		else if (reminderId == 20) return config.prompt20NpcIds();
		else if (reminderId == 21) return config.prompt21NpcIds();
		else if (reminderId == 22) return config.prompt22NpcIds();
		else if (reminderId == 23) return config.prompt23NpcIds();
		else if (reminderId == 24) return config.prompt24NpcIds();
		else if (reminderId == 25) return config.prompt25NpcIds();
		else if (reminderId == 26) return config.prompt26NpcIds();
		else if (reminderId == 27) return config.prompt27NpcIds();
		else if (reminderId == 28) return config.prompt28NpcIds();
		else if (reminderId == 29) return config.prompt29NpcIds();
		else if (reminderId == 30) return config.prompt30NpcIds();
		else if (reminderId == 31) return config.prompt31NpcIds();
		else if (reminderId == 32) return config.prompt32NpcIds();
		else if (reminderId == 33) return config.prompt33NpcIds();
		else if (reminderId == 34) return config.prompt34NpcIds();
		else if (reminderId == 35) return config.prompt35NpcIds();
		else if (reminderId == 36) return config.prompt36NpcIds();
		else if (reminderId == 37) return config.prompt37NpcIds();
		else if (reminderId == 38) return config.prompt38NpcIds();
		else if (reminderId == 39) return config.prompt39NpcIds();
		else if (reminderId == 40) return config.prompt40NpcIds();
		else if (reminderId == 41) return config.prompt41NpcIds();
		else if (reminderId == 42) return config.prompt42NpcIds();
		else if (reminderId == 43) return config.prompt43NpcIds();
		else if (reminderId == 44) return config.prompt44NpcIds();
		else if (reminderId == 45) return config.prompt45NpcIds();
		else if (reminderId == 46) return config.prompt46NpcIds();
		else if (reminderId == 47) return config.prompt47NpcIds();
		else if (reminderId == 48) return config.prompt48NpcIds();
		else if (reminderId == 49) return config.prompt49NpcIds();
		else if (reminderId == 50) return config.prompt50NpcIds();
		else if (reminderId == 51) return config.prompt51NpcIds();
		else if (reminderId == 52) return config.prompt52NpcIds();
		else if (reminderId == 53) return config.prompt53NpcIds();
		else if (reminderId == 54) return config.prompt54NpcIds();
		else if (reminderId == 55) return config.prompt55NpcIds();
		else if (reminderId == 56) return config.prompt56NpcIds();
		else if (reminderId == 57) return config.prompt57NpcIds();
		else if (reminderId == 58) return config.prompt58NpcIds();
		else if (reminderId == 59) return config.prompt59NpcIds();
		else if (reminderId == 60) return config.prompt60NpcIds();
		else if (reminderId == 61) return config.prompt61NpcIds();
		else if (reminderId == 62) return config.prompt62NpcIds();
		else if (reminderId == 63) return config.prompt63NpcIds();
		else if (reminderId == 64) return config.prompt64NpcIds();
		else if (reminderId == 65) return config.prompt65NpcIds();
		else if (reminderId == 66) return config.prompt66NpcIds();
		else if (reminderId == 67) return config.prompt67NpcIds();
		else if (reminderId == 68) return config.prompt68NpcIds();
		else if (reminderId == 69) return config.prompt69NpcIds();
		else if (reminderId == 70) return config.prompt70NpcIds();
		else if (reminderId == 71) return config.prompt71NpcIds();
		else if (reminderId == 72) return config.prompt72NpcIds();
		else if (reminderId == 73) return config.prompt73NpcIds();
		else if (reminderId == 74) return config.prompt74NpcIds();
		else if (reminderId == 75) return config.prompt75NpcIds();
		else if (reminderId == 76) return config.prompt76NpcIds();
		else if (reminderId == 77) return config.prompt77NpcIds();
		else if (reminderId == 78) return config.prompt78NpcIds();
		else if (reminderId == 79) return config.prompt79NpcIds();
		else if (reminderId == 80) return config.prompt80NpcIds();
		else if (reminderId == 81) return config.prompt81NpcIds();
		else if (reminderId == 82) return config.prompt82NpcIds();
		else if (reminderId == 83) return config.prompt83NpcIds();
		else if (reminderId == 84) return config.prompt84NpcIds();
		else if (reminderId == 85) return config.prompt85NpcIds();
		else if (reminderId == 86) return config.prompt86NpcIds();
		else if (reminderId == 87) return config.prompt87NpcIds();
		else if (reminderId == 88) return config.prompt88NpcIds();
		else if (reminderId == 89) return config.prompt89NpcIds();
		else if (reminderId == 90) return config.prompt90NpcIds();
		else if (reminderId == 91) return config.prompt91NpcIds();
		else if (reminderId == 92) return config.prompt92NpcIds();
		else if (reminderId == 93) return config.prompt93NpcIds();
		else if (reminderId == 94) return config.prompt94NpcIds();
		else if (reminderId == 95) return config.prompt95NpcIds();
		else if (reminderId == 96) return config.prompt96NpcIds();
		else if (reminderId == 97) return config.prompt97NpcIds();
		else if (reminderId == 98) return config.prompt98NpcIds();
		else if (reminderId == 99) return config.prompt99NpcIds();
		else if (reminderId == 100) return config.prompt100NpcIds();
		else return "";
	}

	String getItemIds(int reminderId)
	{
		if (reminderId == 1) return config.prompt1ItemIds();
		else if (reminderId == 2) return config.prompt2ItemIds();
		else if (reminderId == 3) return config.prompt3ItemIds();
		else if (reminderId == 4) return config.prompt4ItemIds();
		else if (reminderId == 5) return config.prompt5ItemIds();
		else if (reminderId == 6) return config.prompt6ItemIds();
		else if (reminderId == 7) return config.prompt7ItemIds();
		else if (reminderId == 8) return config.prompt8ItemIds();
		else if (reminderId == 9) return config.prompt9ItemIds();
		else if (reminderId == 10) return config.prompt10ItemIds();
		else if (reminderId == 11) return config.prompt11ItemIds();
		else if (reminderId == 12) return config.prompt12ItemIds();
		else if (reminderId == 13) return config.prompt13ItemIds();
		else if (reminderId == 14) return config.prompt14ItemIds();
		else if (reminderId == 15) return config.prompt15ItemIds();
		else if (reminderId == 16) return config.prompt16ItemIds();
		else if (reminderId == 17) return config.prompt17ItemIds();
		else if (reminderId == 18) return config.prompt18ItemIds();
		else if (reminderId == 19) return config.prompt19ItemIds();
		else if (reminderId == 20) return config.prompt20ItemIds();
		else if (reminderId == 21) return config.prompt21ItemIds();
		else if (reminderId == 22) return config.prompt22ItemIds();
		else if (reminderId == 23) return config.prompt23ItemIds();
		else if (reminderId == 24) return config.prompt24ItemIds();
		else if (reminderId == 25) return config.prompt25ItemIds();
		else if (reminderId == 26) return config.prompt26ItemIds();
		else if (reminderId == 27) return config.prompt27ItemIds();
		else if (reminderId == 28) return config.prompt28ItemIds();
		else if (reminderId == 29) return config.prompt29ItemIds();
		else if (reminderId == 30) return config.prompt30ItemIds();
		else if (reminderId == 31) return config.prompt31ItemIds();
		else if (reminderId == 32) return config.prompt32ItemIds();
		else if (reminderId == 33) return config.prompt33ItemIds();
		else if (reminderId == 34) return config.prompt34ItemIds();
		else if (reminderId == 35) return config.prompt35ItemIds();
		else if (reminderId == 36) return config.prompt36ItemIds();
		else if (reminderId == 37) return config.prompt37ItemIds();
		else if (reminderId == 38) return config.prompt38ItemIds();
		else if (reminderId == 39) return config.prompt39ItemIds();
		else if (reminderId == 40) return config.prompt40ItemIds();
		else if (reminderId == 41) return config.prompt41ItemIds();
		else if (reminderId == 42) return config.prompt42ItemIds();
		else if (reminderId == 43) return config.prompt43ItemIds();
		else if (reminderId == 44) return config.prompt44ItemIds();
		else if (reminderId == 45) return config.prompt45ItemIds();
		else if (reminderId == 46) return config.prompt46ItemIds();
		else if (reminderId == 47) return config.prompt47ItemIds();
		else if (reminderId == 48) return config.prompt48ItemIds();
		else if (reminderId == 49) return config.prompt49ItemIds();
		else if (reminderId == 50) return config.prompt50ItemIds();
		else if (reminderId == 51) return config.prompt51ItemIds();
		else if (reminderId == 52) return config.prompt52ItemIds();
		else if (reminderId == 53) return config.prompt53ItemIds();
		else if (reminderId == 54) return config.prompt54ItemIds();
		else if (reminderId == 55) return config.prompt55ItemIds();
		else if (reminderId == 56) return config.prompt56ItemIds();
		else if (reminderId == 57) return config.prompt57ItemIds();
		else if (reminderId == 58) return config.prompt58ItemIds();
		else if (reminderId == 59) return config.prompt59ItemIds();
		else if (reminderId == 60) return config.prompt60ItemIds();
		else if (reminderId == 61) return config.prompt61ItemIds();
		else if (reminderId == 62) return config.prompt62ItemIds();
		else if (reminderId == 63) return config.prompt63ItemIds();
		else if (reminderId == 64) return config.prompt64ItemIds();
		else if (reminderId == 65) return config.prompt65ItemIds();
		else if (reminderId == 66) return config.prompt66ItemIds();
		else if (reminderId == 67) return config.prompt67ItemIds();
		else if (reminderId == 68) return config.prompt68ItemIds();
		else if (reminderId == 69) return config.prompt69ItemIds();
		else if (reminderId == 70) return config.prompt70ItemIds();
		else if (reminderId == 71) return config.prompt71ItemIds();
		else if (reminderId == 72) return config.prompt72ItemIds();
		else if (reminderId == 73) return config.prompt73ItemIds();
		else if (reminderId == 74) return config.prompt74ItemIds();
		else if (reminderId == 75) return config.prompt75ItemIds();
		else if (reminderId == 76) return config.prompt76ItemIds();
		else if (reminderId == 77) return config.prompt77ItemIds();
		else if (reminderId == 78) return config.prompt78ItemIds();
		else if (reminderId == 79) return config.prompt79ItemIds();
		else if (reminderId == 80) return config.prompt80ItemIds();
		else if (reminderId == 81) return config.prompt81ItemIds();
		else if (reminderId == 82) return config.prompt82ItemIds();
		else if (reminderId == 83) return config.prompt83ItemIds();
		else if (reminderId == 84) return config.prompt84ItemIds();
		else if (reminderId == 85) return config.prompt85ItemIds();
		else if (reminderId == 86) return config.prompt86ItemIds();
		else if (reminderId == 87) return config.prompt87ItemIds();
		else if (reminderId == 88) return config.prompt88ItemIds();
		else if (reminderId == 89) return config.prompt89ItemIds();
		else if (reminderId == 90) return config.prompt90ItemIds();
		else if (reminderId == 91) return config.prompt91ItemIds();
		else if (reminderId == 92) return config.prompt92ItemIds();
		else if (reminderId == 93) return config.prompt93ItemIds();
		else if (reminderId == 94) return config.prompt94ItemIds();
		else if (reminderId == 95) return config.prompt95ItemIds();
		else if (reminderId == 96) return config.prompt96ItemIds();
		else if (reminderId == 97) return config.prompt97ItemIds();
		else if (reminderId == 98) return config.prompt98ItemIds();
		else if (reminderId == 99) return config.prompt99ItemIds();
		else if (reminderId == 100) return config.prompt100ItemIds();
		else return "";
	}

	String getChatPatterns(int reminderId)
	{
		if (reminderId == 1) return config.prompt1ChatPatterns();
		else if (reminderId == 2) return config.prompt2ChatPatterns();
		else if (reminderId == 3) return config.prompt3ChatPatterns();
		else if (reminderId == 4) return config.prompt4ChatPatterns();
		else if (reminderId == 5) return config.prompt5ChatPatterns();
		else if (reminderId == 6) return config.prompt6ChatPatterns();
		else if (reminderId == 7) return config.prompt7ChatPatterns();
		else if (reminderId == 8) return config.prompt8ChatPatterns();
		else if (reminderId == 9) return config.prompt9ChatPatterns();
		else if (reminderId == 10) return config.prompt10ChatPatterns();
		else if (reminderId == 11) return config.prompt11ChatPatterns();
		else if (reminderId == 12) return config.prompt12ChatPatterns();
		else if (reminderId == 13) return config.prompt13ChatPatterns();
		else if (reminderId == 14) return config.prompt14ChatPatterns();
		else if (reminderId == 15) return config.prompt15ChatPatterns();
		else if (reminderId == 16) return config.prompt16ChatPatterns();
		else if (reminderId == 17) return config.prompt17ChatPatterns();
		else if (reminderId == 18) return config.prompt18ChatPatterns();
		else if (reminderId == 19) return config.prompt19ChatPatterns();
		else if (reminderId == 20) return config.prompt20ChatPatterns();
		else if (reminderId == 21) return config.prompt21ChatPatterns();
		else if (reminderId == 22) return config.prompt22ChatPatterns();
		else if (reminderId == 23) return config.prompt23ChatPatterns();
		else if (reminderId == 24) return config.prompt24ChatPatterns();
		else if (reminderId == 25) return config.prompt25ChatPatterns();
		else if (reminderId == 26) return config.prompt26ChatPatterns();
		else if (reminderId == 27) return config.prompt27ChatPatterns();
		else if (reminderId == 28) return config.prompt28ChatPatterns();
		else if (reminderId == 29) return config.prompt29ChatPatterns();
		else if (reminderId == 30) return config.prompt30ChatPatterns();
		else if (reminderId == 31) return config.prompt31ChatPatterns();
		else if (reminderId == 32) return config.prompt32ChatPatterns();
		else if (reminderId == 33) return config.prompt33ChatPatterns();
		else if (reminderId == 34) return config.prompt34ChatPatterns();
		else if (reminderId == 35) return config.prompt35ChatPatterns();
		else if (reminderId == 36) return config.prompt36ChatPatterns();
		else if (reminderId == 37) return config.prompt37ChatPatterns();
		else if (reminderId == 38) return config.prompt38ChatPatterns();
		else if (reminderId == 39) return config.prompt39ChatPatterns();
		else if (reminderId == 40) return config.prompt40ChatPatterns();
		else if (reminderId == 41) return config.prompt41ChatPatterns();
		else if (reminderId == 42) return config.prompt42ChatPatterns();
		else if (reminderId == 43) return config.prompt43ChatPatterns();
		else if (reminderId == 44) return config.prompt44ChatPatterns();
		else if (reminderId == 45) return config.prompt45ChatPatterns();
		else if (reminderId == 46) return config.prompt46ChatPatterns();
		else if (reminderId == 47) return config.prompt47ChatPatterns();
		else if (reminderId == 48) return config.prompt48ChatPatterns();
		else if (reminderId == 49) return config.prompt49ChatPatterns();
		else if (reminderId == 50) return config.prompt50ChatPatterns();
		else if (reminderId == 51) return config.prompt51ChatPatterns();
		else if (reminderId == 52) return config.prompt52ChatPatterns();
		else if (reminderId == 53) return config.prompt53ChatPatterns();
		else if (reminderId == 54) return config.prompt54ChatPatterns();
		else if (reminderId == 55) return config.prompt55ChatPatterns();
		else if (reminderId == 56) return config.prompt56ChatPatterns();
		else if (reminderId == 57) return config.prompt57ChatPatterns();
		else if (reminderId == 58) return config.prompt58ChatPatterns();
		else if (reminderId == 59) return config.prompt59ChatPatterns();
		else if (reminderId == 60) return config.prompt60ChatPatterns();
		else if (reminderId == 61) return config.prompt61ChatPatterns();
		else if (reminderId == 62) return config.prompt62ChatPatterns();
		else if (reminderId == 63) return config.prompt63ChatPatterns();
		else if (reminderId == 64) return config.prompt64ChatPatterns();
		else if (reminderId == 65) return config.prompt65ChatPatterns();
		else if (reminderId == 66) return config.prompt66ChatPatterns();
		else if (reminderId == 67) return config.prompt67ChatPatterns();
		else if (reminderId == 68) return config.prompt68ChatPatterns();
		else if (reminderId == 69) return config.prompt69ChatPatterns();
		else if (reminderId == 70) return config.prompt70ChatPatterns();
		else if (reminderId == 71) return config.prompt71ChatPatterns();
		else if (reminderId == 72) return config.prompt72ChatPatterns();
		else if (reminderId == 73) return config.prompt73ChatPatterns();
		else if (reminderId == 74) return config.prompt74ChatPatterns();
		else if (reminderId == 75) return config.prompt75ChatPatterns();
		else if (reminderId == 76) return config.prompt76ChatPatterns();
		else if (reminderId == 77) return config.prompt77ChatPatterns();
		else if (reminderId == 78) return config.prompt78ChatPatterns();
		else if (reminderId == 79) return config.prompt79ChatPatterns();
		else if (reminderId == 80) return config.prompt80ChatPatterns();
		else if (reminderId == 81) return config.prompt81ChatPatterns();
		else if (reminderId == 82) return config.prompt82ChatPatterns();
		else if (reminderId == 83) return config.prompt83ChatPatterns();
		else if (reminderId == 84) return config.prompt84ChatPatterns();
		else if (reminderId == 85) return config.prompt85ChatPatterns();
		else if (reminderId == 86) return config.prompt86ChatPatterns();
		else if (reminderId == 87) return config.prompt87ChatPatterns();
		else if (reminderId == 88) return config.prompt88ChatPatterns();
		else if (reminderId == 89) return config.prompt89ChatPatterns();
		else if (reminderId == 90) return config.prompt90ChatPatterns();
		else if (reminderId == 91) return config.prompt91ChatPatterns();
		else if (reminderId == 92) return config.prompt92ChatPatterns();
		else if (reminderId == 93) return config.prompt93ChatPatterns();
		else if (reminderId == 94) return config.prompt94ChatPatterns();
		else if (reminderId == 95) return config.prompt95ChatPatterns();
		else if (reminderId == 96) return config.prompt96ChatPatterns();
		else if (reminderId == 97) return config.prompt97ChatPatterns();
		else if (reminderId == 98) return config.prompt98ChatPatterns();
		else if (reminderId == 99) return config.prompt99ChatPatterns();
		else if (reminderId == 100) return config.prompt100ChatPatterns();
		else return "";
	}

	Location getLocation(int reminderId)
	{
		if (reminderId == 1) return config.prompt1Location();
		else if (reminderId == 2) return config.prompt2Location();
		else if (reminderId == 3) return config.prompt3Location();
		else if (reminderId == 4) return config.prompt4Location();
		else if (reminderId == 5) return config.prompt5Location();
		else if (reminderId == 6) return config.prompt6Location();
		else if (reminderId == 7) return config.prompt7Location();
		else if (reminderId == 8) return config.prompt8Location();
		else if (reminderId == 9) return config.prompt9Location();
		else if (reminderId == 10) return config.prompt10Location();
		else if (reminderId == 11) return config.prompt11Location();
		else if (reminderId == 12) return config.prompt12Location();
		else if (reminderId == 13) return config.prompt13Location();
		else if (reminderId == 14) return config.prompt14Location();
		else if (reminderId == 15) return config.prompt15Location();
		else if (reminderId == 16) return config.prompt16Location();
		else if (reminderId == 17) return config.prompt17Location();
		else if (reminderId == 18) return config.prompt18Location();
		else if (reminderId == 19) return config.prompt19Location();
		else if (reminderId == 20) return config.prompt20Location();
		else if (reminderId == 21) return config.prompt21Location();
		else if (reminderId == 22) return config.prompt22Location();
		else if (reminderId == 23) return config.prompt23Location();
		else if (reminderId == 24) return config.prompt24Location();
		else if (reminderId == 25) return config.prompt25Location();
		else if (reminderId == 26) return config.prompt26Location();
		else if (reminderId == 27) return config.prompt27Location();
		else if (reminderId == 28) return config.prompt28Location();
		else if (reminderId == 29) return config.prompt29Location();
		else if (reminderId == 30) return config.prompt30Location();
		else if (reminderId == 31) return config.prompt31Location();
		else if (reminderId == 32) return config.prompt32Location();
		else if (reminderId == 33) return config.prompt33Location();
		else if (reminderId == 34) return config.prompt34Location();
		else if (reminderId == 35) return config.prompt35Location();
		else if (reminderId == 36) return config.prompt36Location();
		else if (reminderId == 37) return config.prompt37Location();
		else if (reminderId == 38) return config.prompt38Location();
		else if (reminderId == 39) return config.prompt39Location();
		else if (reminderId == 40) return config.prompt40Location();
		else if (reminderId == 41) return config.prompt41Location();
		else if (reminderId == 42) return config.prompt42Location();
		else if (reminderId == 43) return config.prompt43Location();
		else if (reminderId == 44) return config.prompt44Location();
		else if (reminderId == 45) return config.prompt45Location();
		else if (reminderId == 46) return config.prompt46Location();
		else if (reminderId == 47) return config.prompt47Location();
		else if (reminderId == 48) return config.prompt48Location();
		else if (reminderId == 49) return config.prompt49Location();
		else if (reminderId == 50) return config.prompt50Location();
		else if (reminderId == 51) return config.prompt51Location();
		else if (reminderId == 52) return config.prompt52Location();
		else if (reminderId == 53) return config.prompt53Location();
		else if (reminderId == 54) return config.prompt54Location();
		else if (reminderId == 55) return config.prompt55Location();
		else if (reminderId == 56) return config.prompt56Location();
		else if (reminderId == 57) return config.prompt57Location();
		else if (reminderId == 58) return config.prompt58Location();
		else if (reminderId == 59) return config.prompt59Location();
		else if (reminderId == 60) return config.prompt60Location();
		else if (reminderId == 61) return config.prompt61Location();
		else if (reminderId == 62) return config.prompt62Location();
		else if (reminderId == 63) return config.prompt63Location();
		else if (reminderId == 64) return config.prompt64Location();
		else if (reminderId == 65) return config.prompt65Location();
		else if (reminderId == 66) return config.prompt66Location();
		else if (reminderId == 67) return config.prompt67Location();
		else if (reminderId == 68) return config.prompt68Location();
		else if (reminderId == 69) return config.prompt69Location();
		else if (reminderId == 70) return config.prompt70Location();
		else if (reminderId == 71) return config.prompt71Location();
		else if (reminderId == 72) return config.prompt72Location();
		else if (reminderId == 73) return config.prompt73Location();
		else if (reminderId == 74) return config.prompt74Location();
		else if (reminderId == 75) return config.prompt75Location();
		else if (reminderId == 76) return config.prompt76Location();
		else if (reminderId == 77) return config.prompt77Location();
		else if (reminderId == 78) return config.prompt78Location();
		else if (reminderId == 79) return config.prompt79Location();
		else if (reminderId == 80) return config.prompt80Location();
		else if (reminderId == 81) return config.prompt81Location();
		else if (reminderId == 82) return config.prompt82Location();
		else if (reminderId == 83) return config.prompt83Location();
		else if (reminderId == 84) return config.prompt84Location();
		else if (reminderId == 85) return config.prompt85Location();
		else if (reminderId == 86) return config.prompt86Location();
		else if (reminderId == 87) return config.prompt87Location();
		else if (reminderId == 88) return config.prompt88Location();
		else if (reminderId == 89) return config.prompt89Location();
		else if (reminderId == 90) return config.prompt90Location();
		else if (reminderId == 91) return config.prompt91Location();
		else if (reminderId == 92) return config.prompt92Location();
		else if (reminderId == 93) return config.prompt93Location();
		else if (reminderId == 94) return config.prompt94Location();
		else if (reminderId == 95) return config.prompt95Location();
		else if (reminderId == 96) return config.prompt96Location();
		else if (reminderId == 97) return config.prompt97Location();
		else if (reminderId == 98) return config.prompt98Location();
		else if (reminderId == 99) return config.prompt99Location();
		else if (reminderId == 100) return config.prompt100Location();
		else return Location.IN_LIST;
	}

	RSAnchorType getPanelAnchorType(int reminderId)
	{
		if (reminderId == 1) return config.prompt1PanelAnchorType();
		else if (reminderId == 2) return config.prompt2PanelAnchorType();
		else if (reminderId == 3) return config.prompt3PanelAnchorType();
		else if (reminderId == 4) return config.prompt4PanelAnchorType();
		else if (reminderId == 5) return config.prompt5PanelAnchorType();
		else if (reminderId == 6) return config.prompt6PanelAnchorType();
		else if (reminderId == 7) return config.prompt7PanelAnchorType();
		else if (reminderId == 8) return config.prompt8PanelAnchorType();
		else if (reminderId == 9) return config.prompt9PanelAnchorType();
		else if (reminderId == 10) return config.prompt10PanelAnchorType();
		else if (reminderId == 11) return config.prompt11PanelAnchorType();
		else if (reminderId == 12) return config.prompt12PanelAnchorType();
		else if (reminderId == 13) return config.prompt13PanelAnchorType();
		else if (reminderId == 14) return config.prompt14PanelAnchorType();
		else if (reminderId == 15) return config.prompt15PanelAnchorType();
		else if (reminderId == 16) return config.prompt16PanelAnchorType();
		else if (reminderId == 17) return config.prompt17PanelAnchorType();
		else if (reminderId == 18) return config.prompt18PanelAnchorType();
		else if (reminderId == 19) return config.prompt19PanelAnchorType();
		else if (reminderId == 20) return config.prompt20PanelAnchorType();
		else if (reminderId == 21) return config.prompt21PanelAnchorType();
		else if (reminderId == 22) return config.prompt22PanelAnchorType();
		else if (reminderId == 23) return config.prompt23PanelAnchorType();
		else if (reminderId == 24) return config.prompt24PanelAnchorType();
		else if (reminderId == 25) return config.prompt25PanelAnchorType();
		else if (reminderId == 26) return config.prompt26PanelAnchorType();
		else if (reminderId == 27) return config.prompt27PanelAnchorType();
		else if (reminderId == 28) return config.prompt28PanelAnchorType();
		else if (reminderId == 29) return config.prompt29PanelAnchorType();
		else if (reminderId == 30) return config.prompt30PanelAnchorType();
		else if (reminderId == 31) return config.prompt31PanelAnchorType();
		else if (reminderId == 32) return config.prompt32PanelAnchorType();
		else if (reminderId == 33) return config.prompt33PanelAnchorType();
		else if (reminderId == 34) return config.prompt34PanelAnchorType();
		else if (reminderId == 35) return config.prompt35PanelAnchorType();
		else if (reminderId == 36) return config.prompt36PanelAnchorType();
		else if (reminderId == 37) return config.prompt37PanelAnchorType();
		else if (reminderId == 38) return config.prompt38PanelAnchorType();
		else if (reminderId == 39) return config.prompt39PanelAnchorType();
		else if (reminderId == 40) return config.prompt40PanelAnchorType();
		else if (reminderId == 41) return config.prompt41PanelAnchorType();
		else if (reminderId == 42) return config.prompt42PanelAnchorType();
		else if (reminderId == 43) return config.prompt43PanelAnchorType();
		else if (reminderId == 44) return config.prompt44PanelAnchorType();
		else if (reminderId == 45) return config.prompt45PanelAnchorType();
		else if (reminderId == 46) return config.prompt46PanelAnchorType();
		else if (reminderId == 47) return config.prompt47PanelAnchorType();
		else if (reminderId == 48) return config.prompt48PanelAnchorType();
		else if (reminderId == 49) return config.prompt49PanelAnchorType();
		else if (reminderId == 50) return config.prompt50PanelAnchorType();
		else if (reminderId == 51) return config.prompt51PanelAnchorType();
		else if (reminderId == 52) return config.prompt52PanelAnchorType();
		else if (reminderId == 53) return config.prompt53PanelAnchorType();
		else if (reminderId == 54) return config.prompt54PanelAnchorType();
		else if (reminderId == 55) return config.prompt55PanelAnchorType();
		else if (reminderId == 56) return config.prompt56PanelAnchorType();
		else if (reminderId == 57) return config.prompt57PanelAnchorType();
		else if (reminderId == 58) return config.prompt58PanelAnchorType();
		else if (reminderId == 59) return config.prompt59PanelAnchorType();
		else if (reminderId == 60) return config.prompt60PanelAnchorType();
		else if (reminderId == 61) return config.prompt61PanelAnchorType();
		else if (reminderId == 62) return config.prompt62PanelAnchorType();
		else if (reminderId == 63) return config.prompt63PanelAnchorType();
		else if (reminderId == 64) return config.prompt64PanelAnchorType();
		else if (reminderId == 65) return config.prompt65PanelAnchorType();
		else if (reminderId == 66) return config.prompt66PanelAnchorType();
		else if (reminderId == 67) return config.prompt67PanelAnchorType();
		else if (reminderId == 68) return config.prompt68PanelAnchorType();
		else if (reminderId == 69) return config.prompt69PanelAnchorType();
		else if (reminderId == 70) return config.prompt70PanelAnchorType();
		else if (reminderId == 71) return config.prompt71PanelAnchorType();
		else if (reminderId == 72) return config.prompt72PanelAnchorType();
		else if (reminderId == 73) return config.prompt73PanelAnchorType();
		else if (reminderId == 74) return config.prompt74PanelAnchorType();
		else if (reminderId == 75) return config.prompt75PanelAnchorType();
		else if (reminderId == 76) return config.prompt76PanelAnchorType();
		else if (reminderId == 77) return config.prompt77PanelAnchorType();
		else if (reminderId == 78) return config.prompt78PanelAnchorType();
		else if (reminderId == 79) return config.prompt79PanelAnchorType();
		else if (reminderId == 80) return config.prompt80PanelAnchorType();
		else if (reminderId == 81) return config.prompt81PanelAnchorType();
		else if (reminderId == 82) return config.prompt82PanelAnchorType();
		else if (reminderId == 83) return config.prompt83PanelAnchorType();
		else if (reminderId == 84) return config.prompt84PanelAnchorType();
		else if (reminderId == 85) return config.prompt85PanelAnchorType();
		else if (reminderId == 86) return config.prompt86PanelAnchorType();
		else if (reminderId == 87) return config.prompt87PanelAnchorType();
		else if (reminderId == 88) return config.prompt88PanelAnchorType();
		else if (reminderId == 89) return config.prompt89PanelAnchorType();
		else if (reminderId == 90) return config.prompt90PanelAnchorType();
		else if (reminderId == 91) return config.prompt91PanelAnchorType();
		else if (reminderId == 92) return config.prompt92PanelAnchorType();
		else if (reminderId == 93) return config.prompt93PanelAnchorType();
		else if (reminderId == 94) return config.prompt94PanelAnchorType();
		else if (reminderId == 95) return config.prompt95PanelAnchorType();
		else if (reminderId == 96) return config.prompt96PanelAnchorType();
		else if (reminderId == 97) return config.prompt97PanelAnchorType();
		else if (reminderId == 98) return config.prompt98PanelAnchorType();
		else if (reminderId == 99) return config.prompt99PanelAnchorType();
		else if (reminderId == 100) return config.prompt100PanelAnchorType();
		else return RSAnchorType.TOP_LEFT;
	}

	int getPanelAnchorX(int reminderId)
	{
		if (reminderId == 1) return config.prompt1PanelAnchorX();
		else if (reminderId == 2) return config.prompt2PanelAnchorX();
		else if (reminderId == 3) return config.prompt3PanelAnchorX();
		else if (reminderId == 4) return config.prompt4PanelAnchorX();
		else if (reminderId == 5) return config.prompt5PanelAnchorX();
		else if (reminderId == 6) return config.prompt6PanelAnchorX();
		else if (reminderId == 7) return config.prompt7PanelAnchorX();
		else if (reminderId == 8) return config.prompt8PanelAnchorX();
		else if (reminderId == 9) return config.prompt9PanelAnchorX();
		else if (reminderId == 10) return config.prompt10PanelAnchorX();
		else if (reminderId == 11) return config.prompt11PanelAnchorX();
		else if (reminderId == 12) return config.prompt12PanelAnchorX();
		else if (reminderId == 13) return config.prompt13PanelAnchorX();
		else if (reminderId == 14) return config.prompt14PanelAnchorX();
		else if (reminderId == 15) return config.prompt15PanelAnchorX();
		else if (reminderId == 16) return config.prompt16PanelAnchorX();
		else if (reminderId == 17) return config.prompt17PanelAnchorX();
		else if (reminderId == 18) return config.prompt18PanelAnchorX();
		else if (reminderId == 19) return config.prompt19PanelAnchorX();
		else if (reminderId == 20) return config.prompt20PanelAnchorX();
		else if (reminderId == 21) return config.prompt21PanelAnchorX();
		else if (reminderId == 22) return config.prompt22PanelAnchorX();
		else if (reminderId == 23) return config.prompt23PanelAnchorX();
		else if (reminderId == 24) return config.prompt24PanelAnchorX();
		else if (reminderId == 25) return config.prompt25PanelAnchorX();
		else if (reminderId == 26) return config.prompt26PanelAnchorX();
		else if (reminderId == 27) return config.prompt27PanelAnchorX();
		else if (reminderId == 28) return config.prompt28PanelAnchorX();
		else if (reminderId == 29) return config.prompt29PanelAnchorX();
		else if (reminderId == 30) return config.prompt30PanelAnchorX();
		else if (reminderId == 31) return config.prompt31PanelAnchorX();
		else if (reminderId == 32) return config.prompt32PanelAnchorX();
		else if (reminderId == 33) return config.prompt33PanelAnchorX();
		else if (reminderId == 34) return config.prompt34PanelAnchorX();
		else if (reminderId == 35) return config.prompt35PanelAnchorX();
		else if (reminderId == 36) return config.prompt36PanelAnchorX();
		else if (reminderId == 37) return config.prompt37PanelAnchorX();
		else if (reminderId == 38) return config.prompt38PanelAnchorX();
		else if (reminderId == 39) return config.prompt39PanelAnchorX();
		else if (reminderId == 40) return config.prompt40PanelAnchorX();
		else if (reminderId == 41) return config.prompt41PanelAnchorX();
		else if (reminderId == 42) return config.prompt42PanelAnchorX();
		else if (reminderId == 43) return config.prompt43PanelAnchorX();
		else if (reminderId == 44) return config.prompt44PanelAnchorX();
		else if (reminderId == 45) return config.prompt45PanelAnchorX();
		else if (reminderId == 46) return config.prompt46PanelAnchorX();
		else if (reminderId == 47) return config.prompt47PanelAnchorX();
		else if (reminderId == 48) return config.prompt48PanelAnchorX();
		else if (reminderId == 49) return config.prompt49PanelAnchorX();
		else if (reminderId == 50) return config.prompt50PanelAnchorX();
		else if (reminderId == 51) return config.prompt51PanelAnchorX();
		else if (reminderId == 52) return config.prompt52PanelAnchorX();
		else if (reminderId == 53) return config.prompt53PanelAnchorX();
		else if (reminderId == 54) return config.prompt54PanelAnchorX();
		else if (reminderId == 55) return config.prompt55PanelAnchorX();
		else if (reminderId == 56) return config.prompt56PanelAnchorX();
		else if (reminderId == 57) return config.prompt57PanelAnchorX();
		else if (reminderId == 58) return config.prompt58PanelAnchorX();
		else if (reminderId == 59) return config.prompt59PanelAnchorX();
		else if (reminderId == 60) return config.prompt60PanelAnchorX();
		else if (reminderId == 61) return config.prompt61PanelAnchorX();
		else if (reminderId == 62) return config.prompt62PanelAnchorX();
		else if (reminderId == 63) return config.prompt63PanelAnchorX();
		else if (reminderId == 64) return config.prompt64PanelAnchorX();
		else if (reminderId == 65) return config.prompt65PanelAnchorX();
		else if (reminderId == 66) return config.prompt66PanelAnchorX();
		else if (reminderId == 67) return config.prompt67PanelAnchorX();
		else if (reminderId == 68) return config.prompt68PanelAnchorX();
		else if (reminderId == 69) return config.prompt69PanelAnchorX();
		else if (reminderId == 70) return config.prompt70PanelAnchorX();
		else if (reminderId == 71) return config.prompt71PanelAnchorX();
		else if (reminderId == 72) return config.prompt72PanelAnchorX();
		else if (reminderId == 73) return config.prompt73PanelAnchorX();
		else if (reminderId == 74) return config.prompt74PanelAnchorX();
		else if (reminderId == 75) return config.prompt75PanelAnchorX();
		else if (reminderId == 76) return config.prompt76PanelAnchorX();
		else if (reminderId == 77) return config.prompt77PanelAnchorX();
		else if (reminderId == 78) return config.prompt78PanelAnchorX();
		else if (reminderId == 79) return config.prompt79PanelAnchorX();
		else if (reminderId == 80) return config.prompt80PanelAnchorX();
		else if (reminderId == 81) return config.prompt81PanelAnchorX();
		else if (reminderId == 82) return config.prompt82PanelAnchorX();
		else if (reminderId == 83) return config.prompt83PanelAnchorX();
		else if (reminderId == 84) return config.prompt84PanelAnchorX();
		else if (reminderId == 85) return config.prompt85PanelAnchorX();
		else if (reminderId == 86) return config.prompt86PanelAnchorX();
		else if (reminderId == 87) return config.prompt87PanelAnchorX();
		else if (reminderId == 88) return config.prompt88PanelAnchorX();
		else if (reminderId == 89) return config.prompt89PanelAnchorX();
		else if (reminderId == 90) return config.prompt90PanelAnchorX();
		else if (reminderId == 91) return config.prompt91PanelAnchorX();
		else if (reminderId == 92) return config.prompt92PanelAnchorX();
		else if (reminderId == 93) return config.prompt93PanelAnchorX();
		else if (reminderId == 94) return config.prompt94PanelAnchorX();
		else if (reminderId == 95) return config.prompt95PanelAnchorX();
		else if (reminderId == 96) return config.prompt96PanelAnchorX();
		else if (reminderId == 97) return config.prompt97PanelAnchorX();
		else if (reminderId == 98) return config.prompt98PanelAnchorX();
		else if (reminderId == 99) return config.prompt99PanelAnchorX();
		else if (reminderId == 100) return config.prompt100PanelAnchorX();
		else return 0;
	}

	int getPanelAnchorY(int reminderId)
	{
		if (reminderId == 1) return config.prompt1PanelAnchorY();
		else if (reminderId == 2) return config.prompt2PanelAnchorY();
		else if (reminderId == 3) return config.prompt3PanelAnchorY();
		else if (reminderId == 4) return config.prompt4PanelAnchorY();
		else if (reminderId == 5) return config.prompt5PanelAnchorY();
		else if (reminderId == 6) return config.prompt6PanelAnchorY();
		else if (reminderId == 7) return config.prompt7PanelAnchorY();
		else if (reminderId == 8) return config.prompt8PanelAnchorY();
		else if (reminderId == 9) return config.prompt9PanelAnchorY();
		else if (reminderId == 10) return config.prompt10PanelAnchorY();
		else if (reminderId == 11) return config.prompt11PanelAnchorY();
		else if (reminderId == 12) return config.prompt12PanelAnchorY();
		else if (reminderId == 13) return config.prompt13PanelAnchorY();
		else if (reminderId == 14) return config.prompt14PanelAnchorY();
		else if (reminderId == 15) return config.prompt15PanelAnchorY();
		else if (reminderId == 16) return config.prompt16PanelAnchorY();
		else if (reminderId == 17) return config.prompt17PanelAnchorY();
		else if (reminderId == 18) return config.prompt18PanelAnchorY();
		else if (reminderId == 19) return config.prompt19PanelAnchorY();
		else if (reminderId == 20) return config.prompt20PanelAnchorY();
		else if (reminderId == 21) return config.prompt21PanelAnchorY();
		else if (reminderId == 22) return config.prompt22PanelAnchorY();
		else if (reminderId == 23) return config.prompt23PanelAnchorY();
		else if (reminderId == 24) return config.prompt24PanelAnchorY();
		else if (reminderId == 25) return config.prompt25PanelAnchorY();
		else if (reminderId == 26) return config.prompt26PanelAnchorY();
		else if (reminderId == 27) return config.prompt27PanelAnchorY();
		else if (reminderId == 28) return config.prompt28PanelAnchorY();
		else if (reminderId == 29) return config.prompt29PanelAnchorY();
		else if (reminderId == 30) return config.prompt30PanelAnchorY();
		else if (reminderId == 31) return config.prompt31PanelAnchorY();
		else if (reminderId == 32) return config.prompt32PanelAnchorY();
		else if (reminderId == 33) return config.prompt33PanelAnchorY();
		else if (reminderId == 34) return config.prompt34PanelAnchorY();
		else if (reminderId == 35) return config.prompt35PanelAnchorY();
		else if (reminderId == 36) return config.prompt36PanelAnchorY();
		else if (reminderId == 37) return config.prompt37PanelAnchorY();
		else if (reminderId == 38) return config.prompt38PanelAnchorY();
		else if (reminderId == 39) return config.prompt39PanelAnchorY();
		else if (reminderId == 40) return config.prompt40PanelAnchorY();
		else if (reminderId == 41) return config.prompt41PanelAnchorY();
		else if (reminderId == 42) return config.prompt42PanelAnchorY();
		else if (reminderId == 43) return config.prompt43PanelAnchorY();
		else if (reminderId == 44) return config.prompt44PanelAnchorY();
		else if (reminderId == 45) return config.prompt45PanelAnchorY();
		else if (reminderId == 46) return config.prompt46PanelAnchorY();
		else if (reminderId == 47) return config.prompt47PanelAnchorY();
		else if (reminderId == 48) return config.prompt48PanelAnchorY();
		else if (reminderId == 49) return config.prompt49PanelAnchorY();
		else if (reminderId == 50) return config.prompt50PanelAnchorY();
		else if (reminderId == 51) return config.prompt51PanelAnchorY();
		else if (reminderId == 52) return config.prompt52PanelAnchorY();
		else if (reminderId == 53) return config.prompt53PanelAnchorY();
		else if (reminderId == 54) return config.prompt54PanelAnchorY();
		else if (reminderId == 55) return config.prompt55PanelAnchorY();
		else if (reminderId == 56) return config.prompt56PanelAnchorY();
		else if (reminderId == 57) return config.prompt57PanelAnchorY();
		else if (reminderId == 58) return config.prompt58PanelAnchorY();
		else if (reminderId == 59) return config.prompt59PanelAnchorY();
		else if (reminderId == 60) return config.prompt60PanelAnchorY();
		else if (reminderId == 61) return config.prompt61PanelAnchorY();
		else if (reminderId == 62) return config.prompt62PanelAnchorY();
		else if (reminderId == 63) return config.prompt63PanelAnchorY();
		else if (reminderId == 64) return config.prompt64PanelAnchorY();
		else if (reminderId == 65) return config.prompt65PanelAnchorY();
		else if (reminderId == 66) return config.prompt66PanelAnchorY();
		else if (reminderId == 67) return config.prompt67PanelAnchorY();
		else if (reminderId == 68) return config.prompt68PanelAnchorY();
		else if (reminderId == 69) return config.prompt69PanelAnchorY();
		else if (reminderId == 70) return config.prompt70PanelAnchorY();
		else if (reminderId == 71) return config.prompt71PanelAnchorY();
		else if (reminderId == 72) return config.prompt72PanelAnchorY();
		else if (reminderId == 73) return config.prompt73PanelAnchorY();
		else if (reminderId == 74) return config.prompt74PanelAnchorY();
		else if (reminderId == 75) return config.prompt75PanelAnchorY();
		else if (reminderId == 76) return config.prompt76PanelAnchorY();
		else if (reminderId == 77) return config.prompt77PanelAnchorY();
		else if (reminderId == 78) return config.prompt78PanelAnchorY();
		else if (reminderId == 79) return config.prompt79PanelAnchorY();
		else if (reminderId == 80) return config.prompt80PanelAnchorY();
		else if (reminderId == 81) return config.prompt81PanelAnchorY();
		else if (reminderId == 82) return config.prompt82PanelAnchorY();
		else if (reminderId == 83) return config.prompt83PanelAnchorY();
		else if (reminderId == 84) return config.prompt84PanelAnchorY();
		else if (reminderId == 85) return config.prompt85PanelAnchorY();
		else if (reminderId == 86) return config.prompt86PanelAnchorY();
		else if (reminderId == 87) return config.prompt87PanelAnchorY();
		else if (reminderId == 88) return config.prompt88PanelAnchorY();
		else if (reminderId == 89) return config.prompt89PanelAnchorY();
		else if (reminderId == 90) return config.prompt90PanelAnchorY();
		else if (reminderId == 91) return config.prompt91PanelAnchorY();
		else if (reminderId == 92) return config.prompt92PanelAnchorY();
		else if (reminderId == 93) return config.prompt93PanelAnchorY();
		else if (reminderId == 94) return config.prompt94PanelAnchorY();
		else if (reminderId == 95) return config.prompt95PanelAnchorY();
		else if (reminderId == 96) return config.prompt96PanelAnchorY();
		else if (reminderId == 97) return config.prompt97PanelAnchorY();
		else if (reminderId == 98) return config.prompt98PanelAnchorY();
		else if (reminderId == 99) return config.prompt99PanelAnchorY();
		else if (reminderId == 100) return config.prompt100PanelAnchorY();
		else return 0;
	}

	int getImageId(int reminderId)
	{
		if (reminderId == 1) return config.prompt1ImageId();
		else if (reminderId == 2) return config.prompt2ImageId();
		else if (reminderId == 3) return config.prompt3ImageId();
		else if (reminderId == 4) return config.prompt4ImageId();
		else if (reminderId == 5) return config.prompt5ImageId();
		else if (reminderId == 6) return config.prompt6ImageId();
		else if (reminderId == 7) return config.prompt7ImageId();
		else if (reminderId == 8) return config.prompt8ImageId();
		else if (reminderId == 9) return config.prompt9ImageId();
		else if (reminderId == 10) return config.prompt10ImageId();
		else if (reminderId == 11) return config.prompt11ImageId();
		else if (reminderId == 12) return config.prompt12ImageId();
		else if (reminderId == 13) return config.prompt13ImageId();
		else if (reminderId == 14) return config.prompt14ImageId();
		else if (reminderId == 15) return config.prompt15ImageId();
		else if (reminderId == 16) return config.prompt16ImageId();
		else if (reminderId == 17) return config.prompt17ImageId();
		else if (reminderId == 18) return config.prompt18ImageId();
		else if (reminderId == 19) return config.prompt19ImageId();
		else if (reminderId == 20) return config.prompt20ImageId();
		else if (reminderId == 21) return config.prompt21ImageId();
		else if (reminderId == 22) return config.prompt22ImageId();
		else if (reminderId == 23) return config.prompt23ImageId();
		else if (reminderId == 24) return config.prompt24ImageId();
		else if (reminderId == 25) return config.prompt25ImageId();
		else if (reminderId == 26) return config.prompt26ImageId();
		else if (reminderId == 27) return config.prompt27ImageId();
		else if (reminderId == 28) return config.prompt28ImageId();
		else if (reminderId == 29) return config.prompt29ImageId();
		else if (reminderId == 30) return config.prompt30ImageId();
		else if (reminderId == 31) return config.prompt31ImageId();
		else if (reminderId == 32) return config.prompt32ImageId();
		else if (reminderId == 33) return config.prompt33ImageId();
		else if (reminderId == 34) return config.prompt34ImageId();
		else if (reminderId == 35) return config.prompt35ImageId();
		else if (reminderId == 36) return config.prompt36ImageId();
		else if (reminderId == 37) return config.prompt37ImageId();
		else if (reminderId == 38) return config.prompt38ImageId();
		else if (reminderId == 39) return config.prompt39ImageId();
		else if (reminderId == 40) return config.prompt40ImageId();
		else if (reminderId == 41) return config.prompt41ImageId();
		else if (reminderId == 42) return config.prompt42ImageId();
		else if (reminderId == 43) return config.prompt43ImageId();
		else if (reminderId == 44) return config.prompt44ImageId();
		else if (reminderId == 45) return config.prompt45ImageId();
		else if (reminderId == 46) return config.prompt46ImageId();
		else if (reminderId == 47) return config.prompt47ImageId();
		else if (reminderId == 48) return config.prompt48ImageId();
		else if (reminderId == 49) return config.prompt49ImageId();
		else if (reminderId == 50) return config.prompt50ImageId();
		else if (reminderId == 51) return config.prompt51ImageId();
		else if (reminderId == 52) return config.prompt52ImageId();
		else if (reminderId == 53) return config.prompt53ImageId();
		else if (reminderId == 54) return config.prompt54ImageId();
		else if (reminderId == 55) return config.prompt55ImageId();
		else if (reminderId == 56) return config.prompt56ImageId();
		else if (reminderId == 57) return config.prompt57ImageId();
		else if (reminderId == 58) return config.prompt58ImageId();
		else if (reminderId == 59) return config.prompt59ImageId();
		else if (reminderId == 60) return config.prompt60ImageId();
		else if (reminderId == 61) return config.prompt61ImageId();
		else if (reminderId == 62) return config.prompt62ImageId();
		else if (reminderId == 63) return config.prompt63ImageId();
		else if (reminderId == 64) return config.prompt64ImageId();
		else if (reminderId == 65) return config.prompt65ImageId();
		else if (reminderId == 66) return config.prompt66ImageId();
		else if (reminderId == 67) return config.prompt67ImageId();
		else if (reminderId == 68) return config.prompt68ImageId();
		else if (reminderId == 69) return config.prompt69ImageId();
		else if (reminderId == 70) return config.prompt70ImageId();
		else if (reminderId == 71) return config.prompt71ImageId();
		else if (reminderId == 72) return config.prompt72ImageId();
		else if (reminderId == 73) return config.prompt73ImageId();
		else if (reminderId == 74) return config.prompt74ImageId();
		else if (reminderId == 75) return config.prompt75ImageId();
		else if (reminderId == 76) return config.prompt76ImageId();
		else if (reminderId == 77) return config.prompt77ImageId();
		else if (reminderId == 78) return config.prompt78ImageId();
		else if (reminderId == 79) return config.prompt79ImageId();
		else if (reminderId == 80) return config.prompt80ImageId();
		else if (reminderId == 81) return config.prompt81ImageId();
		else if (reminderId == 82) return config.prompt82ImageId();
		else if (reminderId == 83) return config.prompt83ImageId();
		else if (reminderId == 84) return config.prompt84ImageId();
		else if (reminderId == 85) return config.prompt85ImageId();
		else if (reminderId == 86) return config.prompt86ImageId();
		else if (reminderId == 87) return config.prompt87ImageId();
		else if (reminderId == 88) return config.prompt88ImageId();
		else if (reminderId == 89) return config.prompt89ImageId();
		else if (reminderId == 90) return config.prompt90ImageId();
		else if (reminderId == 91) return config.prompt91ImageId();
		else if (reminderId == 92) return config.prompt92ImageId();
		else if (reminderId == 93) return config.prompt93ImageId();
		else if (reminderId == 94) return config.prompt94ImageId();
		else if (reminderId == 95) return config.prompt95ImageId();
		else if (reminderId == 96) return config.prompt96ImageId();
		else if (reminderId == 97) return config.prompt97ImageId();
		else if (reminderId == 98) return config.prompt98ImageId();
		else if (reminderId == 99) return config.prompt99ImageId();
		else if (reminderId == 100) return config.prompt100ImageId();
		else return 0;
	}

	int getPanelWidth(int reminderId)
	{
		if (reminderId == 1) return config.prompt1PanelWidth();
		else if (reminderId == 2) return config.prompt2PanelWidth();
		else if (reminderId == 3) return config.prompt3PanelWidth();
		else if (reminderId == 4) return config.prompt4PanelWidth();
		else if (reminderId == 5) return config.prompt5PanelWidth();
		else if (reminderId == 6) return config.prompt6PanelWidth();
		else if (reminderId == 7) return config.prompt7PanelWidth();
		else if (reminderId == 8) return config.prompt8PanelWidth();
		else if (reminderId == 9) return config.prompt9PanelWidth();
		else if (reminderId == 10) return config.prompt10PanelWidth();
		else if (reminderId == 11) return config.prompt11PanelWidth();
		else if (reminderId == 12) return config.prompt12PanelWidth();
		else if (reminderId == 13) return config.prompt13PanelWidth();
		else if (reminderId == 14) return config.prompt14PanelWidth();
		else if (reminderId == 15) return config.prompt15PanelWidth();
		else if (reminderId == 16) return config.prompt16PanelWidth();
		else if (reminderId == 17) return config.prompt17PanelWidth();
		else if (reminderId == 18) return config.prompt18PanelWidth();
		else if (reminderId == 19) return config.prompt19PanelWidth();
		else if (reminderId == 20) return config.prompt20PanelWidth();
		else if (reminderId == 21) return config.prompt21PanelWidth();
		else if (reminderId == 22) return config.prompt22PanelWidth();
		else if (reminderId == 23) return config.prompt23PanelWidth();
		else if (reminderId == 24) return config.prompt24PanelWidth();
		else if (reminderId == 25) return config.prompt25PanelWidth();
		else if (reminderId == 26) return config.prompt26PanelWidth();
		else if (reminderId == 27) return config.prompt27PanelWidth();
		else if (reminderId == 28) return config.prompt28PanelWidth();
		else if (reminderId == 29) return config.prompt29PanelWidth();
		else if (reminderId == 30) return config.prompt30PanelWidth();
		else if (reminderId == 31) return config.prompt31PanelWidth();
		else if (reminderId == 32) return config.prompt32PanelWidth();
		else if (reminderId == 33) return config.prompt33PanelWidth();
		else if (reminderId == 34) return config.prompt34PanelWidth();
		else if (reminderId == 35) return config.prompt35PanelWidth();
		else if (reminderId == 36) return config.prompt36PanelWidth();
		else if (reminderId == 37) return config.prompt37PanelWidth();
		else if (reminderId == 38) return config.prompt38PanelWidth();
		else if (reminderId == 39) return config.prompt39PanelWidth();
		else if (reminderId == 40) return config.prompt40PanelWidth();
		else if (reminderId == 41) return config.prompt41PanelWidth();
		else if (reminderId == 42) return config.prompt42PanelWidth();
		else if (reminderId == 43) return config.prompt43PanelWidth();
		else if (reminderId == 44) return config.prompt44PanelWidth();
		else if (reminderId == 45) return config.prompt45PanelWidth();
		else if (reminderId == 46) return config.prompt46PanelWidth();
		else if (reminderId == 47) return config.prompt47PanelWidth();
		else if (reminderId == 48) return config.prompt48PanelWidth();
		else if (reminderId == 49) return config.prompt49PanelWidth();
		else if (reminderId == 50) return config.prompt50PanelWidth();
		else if (reminderId == 51) return config.prompt51PanelWidth();
		else if (reminderId == 52) return config.prompt52PanelWidth();
		else if (reminderId == 53) return config.prompt53PanelWidth();
		else if (reminderId == 54) return config.prompt54PanelWidth();
		else if (reminderId == 55) return config.prompt55PanelWidth();
		else if (reminderId == 56) return config.prompt56PanelWidth();
		else if (reminderId == 57) return config.prompt57PanelWidth();
		else if (reminderId == 58) return config.prompt58PanelWidth();
		else if (reminderId == 59) return config.prompt59PanelWidth();
		else if (reminderId == 60) return config.prompt60PanelWidth();
		else if (reminderId == 61) return config.prompt61PanelWidth();
		else if (reminderId == 62) return config.prompt62PanelWidth();
		else if (reminderId == 63) return config.prompt63PanelWidth();
		else if (reminderId == 64) return config.prompt64PanelWidth();
		else if (reminderId == 65) return config.prompt65PanelWidth();
		else if (reminderId == 66) return config.prompt66PanelWidth();
		else if (reminderId == 67) return config.prompt67PanelWidth();
		else if (reminderId == 68) return config.prompt68PanelWidth();
		else if (reminderId == 69) return config.prompt69PanelWidth();
		else if (reminderId == 70) return config.prompt70PanelWidth();
		else if (reminderId == 71) return config.prompt71PanelWidth();
		else if (reminderId == 72) return config.prompt72PanelWidth();
		else if (reminderId == 73) return config.prompt73PanelWidth();
		else if (reminderId == 74) return config.prompt74PanelWidth();
		else if (reminderId == 75) return config.prompt75PanelWidth();
		else if (reminderId == 76) return config.prompt76PanelWidth();
		else if (reminderId == 77) return config.prompt77PanelWidth();
		else if (reminderId == 78) return config.prompt78PanelWidth();
		else if (reminderId == 79) return config.prompt79PanelWidth();
		else if (reminderId == 80) return config.prompt80PanelWidth();
		else if (reminderId == 81) return config.prompt81PanelWidth();
		else if (reminderId == 82) return config.prompt82PanelWidth();
		else if (reminderId == 83) return config.prompt83PanelWidth();
		else if (reminderId == 84) return config.prompt84PanelWidth();
		else if (reminderId == 85) return config.prompt85PanelWidth();
		else if (reminderId == 86) return config.prompt86PanelWidth();
		else if (reminderId == 87) return config.prompt87PanelWidth();
		else if (reminderId == 88) return config.prompt88PanelWidth();
		else if (reminderId == 89) return config.prompt89PanelWidth();
		else if (reminderId == 90) return config.prompt90PanelWidth();
		else if (reminderId == 91) return config.prompt91PanelWidth();
		else if (reminderId == 92) return config.prompt92PanelWidth();
		else if (reminderId == 93) return config.prompt93PanelWidth();
		else if (reminderId == 94) return config.prompt94PanelWidth();
		else if (reminderId == 95) return config.prompt95PanelWidth();
		else if (reminderId == 96) return config.prompt96PanelWidth();
		else if (reminderId == 97) return config.prompt97PanelWidth();
		else if (reminderId == 98) return config.prompt98PanelWidth();
		else if (reminderId == 99) return config.prompt99PanelWidth();
		else if (reminderId == 100) return config.prompt100PanelWidth();
		else return 0;
	}

	TextSize getPanelTextSize(int reminderId)
	{
		if (reminderId == 1) return config.prompt1PanelTextSize();
		else if (reminderId == 2) return config.prompt2PanelTextSize();
		else if (reminderId == 3) return config.prompt3PanelTextSize();
		else if (reminderId == 4) return config.prompt4PanelTextSize();
		else if (reminderId == 5) return config.prompt5PanelTextSize();
		else if (reminderId == 6) return config.prompt6PanelTextSize();
		else if (reminderId == 7) return config.prompt7PanelTextSize();
		else if (reminderId == 8) return config.prompt8PanelTextSize();
		else if (reminderId == 9) return config.prompt9PanelTextSize();
		else if (reminderId == 10) return config.prompt10PanelTextSize();
		else if (reminderId == 11) return config.prompt11PanelTextSize();
		else if (reminderId == 12) return config.prompt12PanelTextSize();
		else if (reminderId == 13) return config.prompt13PanelTextSize();
		else if (reminderId == 14) return config.prompt14PanelTextSize();
		else if (reminderId == 15) return config.prompt15PanelTextSize();
		else if (reminderId == 16) return config.prompt16PanelTextSize();
		else if (reminderId == 17) return config.prompt17PanelTextSize();
		else if (reminderId == 18) return config.prompt18PanelTextSize();
		else if (reminderId == 19) return config.prompt19PanelTextSize();
		else if (reminderId == 20) return config.prompt20PanelTextSize();
		else if (reminderId == 21) return config.prompt21PanelTextSize();
		else if (reminderId == 22) return config.prompt22PanelTextSize();
		else if (reminderId == 23) return config.prompt23PanelTextSize();
		else if (reminderId == 24) return config.prompt24PanelTextSize();
		else if (reminderId == 25) return config.prompt25PanelTextSize();
		else if (reminderId == 26) return config.prompt26PanelTextSize();
		else if (reminderId == 27) return config.prompt27PanelTextSize();
		else if (reminderId == 28) return config.prompt28PanelTextSize();
		else if (reminderId == 29) return config.prompt29PanelTextSize();
		else if (reminderId == 30) return config.prompt30PanelTextSize();
		else if (reminderId == 31) return config.prompt31PanelTextSize();
		else if (reminderId == 32) return config.prompt32PanelTextSize();
		else if (reminderId == 33) return config.prompt33PanelTextSize();
		else if (reminderId == 34) return config.prompt34PanelTextSize();
		else if (reminderId == 35) return config.prompt35PanelTextSize();
		else if (reminderId == 36) return config.prompt36PanelTextSize();
		else if (reminderId == 37) return config.prompt37PanelTextSize();
		else if (reminderId == 38) return config.prompt38PanelTextSize();
		else if (reminderId == 39) return config.prompt39PanelTextSize();
		else if (reminderId == 40) return config.prompt40PanelTextSize();
		else if (reminderId == 41) return config.prompt41PanelTextSize();
		else if (reminderId == 42) return config.prompt42PanelTextSize();
		else if (reminderId == 43) return config.prompt43PanelTextSize();
		else if (reminderId == 44) return config.prompt44PanelTextSize();
		else if (reminderId == 45) return config.prompt45PanelTextSize();
		else if (reminderId == 46) return config.prompt46PanelTextSize();
		else if (reminderId == 47) return config.prompt47PanelTextSize();
		else if (reminderId == 48) return config.prompt48PanelTextSize();
		else if (reminderId == 49) return config.prompt49PanelTextSize();
		else if (reminderId == 50) return config.prompt50PanelTextSize();
		else if (reminderId == 51) return config.prompt51PanelTextSize();
		else if (reminderId == 52) return config.prompt52PanelTextSize();
		else if (reminderId == 53) return config.prompt53PanelTextSize();
		else if (reminderId == 54) return config.prompt54PanelTextSize();
		else if (reminderId == 55) return config.prompt55PanelTextSize();
		else if (reminderId == 56) return config.prompt56PanelTextSize();
		else if (reminderId == 57) return config.prompt57PanelTextSize();
		else if (reminderId == 58) return config.prompt58PanelTextSize();
		else if (reminderId == 59) return config.prompt59PanelTextSize();
		else if (reminderId == 60) return config.prompt60PanelTextSize();
		else if (reminderId == 61) return config.prompt61PanelTextSize();
		else if (reminderId == 62) return config.prompt62PanelTextSize();
		else if (reminderId == 63) return config.prompt63PanelTextSize();
		else if (reminderId == 64) return config.prompt64PanelTextSize();
		else if (reminderId == 65) return config.prompt65PanelTextSize();
		else if (reminderId == 66) return config.prompt66PanelTextSize();
		else if (reminderId == 67) return config.prompt67PanelTextSize();
		else if (reminderId == 68) return config.prompt68PanelTextSize();
		else if (reminderId == 69) return config.prompt69PanelTextSize();
		else if (reminderId == 70) return config.prompt70PanelTextSize();
		else if (reminderId == 71) return config.prompt71PanelTextSize();
		else if (reminderId == 72) return config.prompt72PanelTextSize();
		else if (reminderId == 73) return config.prompt73PanelTextSize();
		else if (reminderId == 74) return config.prompt74PanelTextSize();
		else if (reminderId == 75) return config.prompt75PanelTextSize();
		else if (reminderId == 76) return config.prompt76PanelTextSize();
		else if (reminderId == 77) return config.prompt77PanelTextSize();
		else if (reminderId == 78) return config.prompt78PanelTextSize();
		else if (reminderId == 79) return config.prompt79PanelTextSize();
		else if (reminderId == 80) return config.prompt80PanelTextSize();
		else if (reminderId == 81) return config.prompt81PanelTextSize();
		else if (reminderId == 82) return config.prompt82PanelTextSize();
		else if (reminderId == 83) return config.prompt83PanelTextSize();
		else if (reminderId == 84) return config.prompt84PanelTextSize();
		else if (reminderId == 85) return config.prompt85PanelTextSize();
		else if (reminderId == 86) return config.prompt86PanelTextSize();
		else if (reminderId == 87) return config.prompt87PanelTextSize();
		else if (reminderId == 88) return config.prompt88PanelTextSize();
		else if (reminderId == 89) return config.prompt89PanelTextSize();
		else if (reminderId == 90) return config.prompt90PanelTextSize();
		else if (reminderId == 91) return config.prompt91PanelTextSize();
		else if (reminderId == 92) return config.prompt92PanelTextSize();
		else if (reminderId == 93) return config.prompt93PanelTextSize();
		else if (reminderId == 94) return config.prompt94PanelTextSize();
		else if (reminderId == 95) return config.prompt95PanelTextSize();
		else if (reminderId == 96) return config.prompt96PanelTextSize();
		else if (reminderId == 97) return config.prompt97PanelTextSize();
		else if (reminderId == 98) return config.prompt98PanelTextSize();
		else if (reminderId == 99) return config.prompt99PanelTextSize();
		else if (reminderId == 100) return config.prompt100PanelTextSize();
		else return TextSize.SMALL;
	}

	Color getPanelColor(int reminderId)
	{
		if (reminderId == 1) return config.prompt1PanelColor();
		else if (reminderId == 2) return config.prompt2PanelColor();
		else if (reminderId == 3) return config.prompt3PanelColor();
		else if (reminderId == 4) return config.prompt4PanelColor();
		else if (reminderId == 5) return config.prompt5PanelColor();
		else if (reminderId == 6) return config.prompt6PanelColor();
		else if (reminderId == 7) return config.prompt7PanelColor();
		else if (reminderId == 8) return config.prompt8PanelColor();
		else if (reminderId == 9) return config.prompt9PanelColor();
		else if (reminderId == 10) return config.prompt10PanelColor();
		else if (reminderId == 11) return config.prompt11PanelColor();
		else if (reminderId == 12) return config.prompt12PanelColor();
		else if (reminderId == 13) return config.prompt13PanelColor();
		else if (reminderId == 14) return config.prompt14PanelColor();
		else if (reminderId == 15) return config.prompt15PanelColor();
		else if (reminderId == 16) return config.prompt16PanelColor();
		else if (reminderId == 17) return config.prompt17PanelColor();
		else if (reminderId == 18) return config.prompt18PanelColor();
		else if (reminderId == 19) return config.prompt19PanelColor();
		else if (reminderId == 20) return config.prompt20PanelColor();
		else if (reminderId == 21) return config.prompt21PanelColor();
		else if (reminderId == 22) return config.prompt22PanelColor();
		else if (reminderId == 23) return config.prompt23PanelColor();
		else if (reminderId == 24) return config.prompt24PanelColor();
		else if (reminderId == 25) return config.prompt25PanelColor();
		else if (reminderId == 26) return config.prompt26PanelColor();
		else if (reminderId == 27) return config.prompt27PanelColor();
		else if (reminderId == 28) return config.prompt28PanelColor();
		else if (reminderId == 29) return config.prompt29PanelColor();
		else if (reminderId == 30) return config.prompt30PanelColor();
		else if (reminderId == 31) return config.prompt31PanelColor();
		else if (reminderId == 32) return config.prompt32PanelColor();
		else if (reminderId == 33) return config.prompt33PanelColor();
		else if (reminderId == 34) return config.prompt34PanelColor();
		else if (reminderId == 35) return config.prompt35PanelColor();
		else if (reminderId == 36) return config.prompt36PanelColor();
		else if (reminderId == 37) return config.prompt37PanelColor();
		else if (reminderId == 38) return config.prompt38PanelColor();
		else if (reminderId == 39) return config.prompt39PanelColor();
		else if (reminderId == 40) return config.prompt40PanelColor();
		else if (reminderId == 41) return config.prompt41PanelColor();
		else if (reminderId == 42) return config.prompt42PanelColor();
		else if (reminderId == 43) return config.prompt43PanelColor();
		else if (reminderId == 44) return config.prompt44PanelColor();
		else if (reminderId == 45) return config.prompt45PanelColor();
		else if (reminderId == 46) return config.prompt46PanelColor();
		else if (reminderId == 47) return config.prompt47PanelColor();
		else if (reminderId == 48) return config.prompt48PanelColor();
		else if (reminderId == 49) return config.prompt49PanelColor();
		else if (reminderId == 50) return config.prompt50PanelColor();
		else if (reminderId == 51) return config.prompt51PanelColor();
		else if (reminderId == 52) return config.prompt52PanelColor();
		else if (reminderId == 53) return config.prompt53PanelColor();
		else if (reminderId == 54) return config.prompt54PanelColor();
		else if (reminderId == 55) return config.prompt55PanelColor();
		else if (reminderId == 56) return config.prompt56PanelColor();
		else if (reminderId == 57) return config.prompt57PanelColor();
		else if (reminderId == 58) return config.prompt58PanelColor();
		else if (reminderId == 59) return config.prompt59PanelColor();
		else if (reminderId == 60) return config.prompt60PanelColor();
		else if (reminderId == 61) return config.prompt61PanelColor();
		else if (reminderId == 62) return config.prompt62PanelColor();
		else if (reminderId == 63) return config.prompt63PanelColor();
		else if (reminderId == 64) return config.prompt64PanelColor();
		else if (reminderId == 65) return config.prompt65PanelColor();
		else if (reminderId == 66) return config.prompt66PanelColor();
		else if (reminderId == 67) return config.prompt67PanelColor();
		else if (reminderId == 68) return config.prompt68PanelColor();
		else if (reminderId == 69) return config.prompt69PanelColor();
		else if (reminderId == 70) return config.prompt70PanelColor();
		else if (reminderId == 71) return config.prompt71PanelColor();
		else if (reminderId == 72) return config.prompt72PanelColor();
		else if (reminderId == 73) return config.prompt73PanelColor();
		else if (reminderId == 74) return config.prompt74PanelColor();
		else if (reminderId == 75) return config.prompt75PanelColor();
		else if (reminderId == 76) return config.prompt76PanelColor();
		else if (reminderId == 77) return config.prompt77PanelColor();
		else if (reminderId == 78) return config.prompt78PanelColor();
		else if (reminderId == 79) return config.prompt79PanelColor();
		else if (reminderId == 80) return config.prompt80PanelColor();
		else if (reminderId == 81) return config.prompt81PanelColor();
		else if (reminderId == 82) return config.prompt82PanelColor();
		else if (reminderId == 83) return config.prompt83PanelColor();
		else if (reminderId == 84) return config.prompt84PanelColor();
		else if (reminderId == 85) return config.prompt85PanelColor();
		else if (reminderId == 86) return config.prompt86PanelColor();
		else if (reminderId == 87) return config.prompt87PanelColor();
		else if (reminderId == 88) return config.prompt88PanelColor();
		else if (reminderId == 89) return config.prompt89PanelColor();
		else if (reminderId == 90) return config.prompt90PanelColor();
		else if (reminderId == 91) return config.prompt91PanelColor();
		else if (reminderId == 92) return config.prompt92PanelColor();
		else if (reminderId == 93) return config.prompt93PanelColor();
		else if (reminderId == 94) return config.prompt94PanelColor();
		else if (reminderId == 95) return config.prompt95PanelColor();
		else if (reminderId == 96) return config.prompt96PanelColor();
		else if (reminderId == 97) return config.prompt97PanelColor();
		else if (reminderId == 98) return config.prompt98PanelColor();
		else if (reminderId == 99) return config.prompt99PanelColor();
		else if (reminderId == 100) return config.prompt100PanelColor();
		else return Color.WHITE;
	}

	boolean isPanelBorder(int reminderId)
	{
		if (reminderId == 1) return config.prompt1PanelBorder();
		else if (reminderId == 2) return config.prompt2PanelBorder();
		else if (reminderId == 3) return config.prompt3PanelBorder();
		else if (reminderId == 4) return config.prompt4PanelBorder();
		else if (reminderId == 5) return config.prompt5PanelBorder();
		else if (reminderId == 6) return config.prompt6PanelBorder();
		else if (reminderId == 7) return config.prompt7PanelBorder();
		else if (reminderId == 8) return config.prompt8PanelBorder();
		else if (reminderId == 9) return config.prompt9PanelBorder();
		else if (reminderId == 10) return config.prompt10PanelBorder();
		else if (reminderId == 11) return config.prompt11PanelBorder();
		else if (reminderId == 12) return config.prompt12PanelBorder();
		else if (reminderId == 13) return config.prompt13PanelBorder();
		else if (reminderId == 14) return config.prompt14PanelBorder();
		else if (reminderId == 15) return config.prompt15PanelBorder();
		else if (reminderId == 16) return config.prompt16PanelBorder();
		else if (reminderId == 17) return config.prompt17PanelBorder();
		else if (reminderId == 18) return config.prompt18PanelBorder();
		else if (reminderId == 19) return config.prompt19PanelBorder();
		else if (reminderId == 20) return config.prompt20PanelBorder();
		else if (reminderId == 21) return config.prompt21PanelBorder();
		else if (reminderId == 22) return config.prompt22PanelBorder();
		else if (reminderId == 23) return config.prompt23PanelBorder();
		else if (reminderId == 24) return config.prompt24PanelBorder();
		else if (reminderId == 25) return config.prompt25PanelBorder();
		else if (reminderId == 26) return config.prompt26PanelBorder();
		else if (reminderId == 27) return config.prompt27PanelBorder();
		else if (reminderId == 28) return config.prompt28PanelBorder();
		else if (reminderId == 29) return config.prompt29PanelBorder();
		else if (reminderId == 30) return config.prompt30PanelBorder();
		else if (reminderId == 31) return config.prompt31PanelBorder();
		else if (reminderId == 32) return config.prompt32PanelBorder();
		else if (reminderId == 33) return config.prompt33PanelBorder();
		else if (reminderId == 34) return config.prompt34PanelBorder();
		else if (reminderId == 35) return config.prompt35PanelBorder();
		else if (reminderId == 36) return config.prompt36PanelBorder();
		else if (reminderId == 37) return config.prompt37PanelBorder();
		else if (reminderId == 38) return config.prompt38PanelBorder();
		else if (reminderId == 39) return config.prompt39PanelBorder();
		else if (reminderId == 40) return config.prompt40PanelBorder();
		else if (reminderId == 41) return config.prompt41PanelBorder();
		else if (reminderId == 42) return config.prompt42PanelBorder();
		else if (reminderId == 43) return config.prompt43PanelBorder();
		else if (reminderId == 44) return config.prompt44PanelBorder();
		else if (reminderId == 45) return config.prompt45PanelBorder();
		else if (reminderId == 46) return config.prompt46PanelBorder();
		else if (reminderId == 47) return config.prompt47PanelBorder();
		else if (reminderId == 48) return config.prompt48PanelBorder();
		else if (reminderId == 49) return config.prompt49PanelBorder();
		else if (reminderId == 50) return config.prompt50PanelBorder();
		else if (reminderId == 51) return config.prompt51PanelBorder();
		else if (reminderId == 52) return config.prompt52PanelBorder();
		else if (reminderId == 53) return config.prompt53PanelBorder();
		else if (reminderId == 54) return config.prompt54PanelBorder();
		else if (reminderId == 55) return config.prompt55PanelBorder();
		else if (reminderId == 56) return config.prompt56PanelBorder();
		else if (reminderId == 57) return config.prompt57PanelBorder();
		else if (reminderId == 58) return config.prompt58PanelBorder();
		else if (reminderId == 59) return config.prompt59PanelBorder();
		else if (reminderId == 60) return config.prompt60PanelBorder();
		else if (reminderId == 61) return config.prompt61PanelBorder();
		else if (reminderId == 62) return config.prompt62PanelBorder();
		else if (reminderId == 63) return config.prompt63PanelBorder();
		else if (reminderId == 64) return config.prompt64PanelBorder();
		else if (reminderId == 65) return config.prompt65PanelBorder();
		else if (reminderId == 66) return config.prompt66PanelBorder();
		else if (reminderId == 67) return config.prompt67PanelBorder();
		else if (reminderId == 68) return config.prompt68PanelBorder();
		else if (reminderId == 69) return config.prompt69PanelBorder();
		else if (reminderId == 70) return config.prompt70PanelBorder();
		else if (reminderId == 71) return config.prompt71PanelBorder();
		else if (reminderId == 72) return config.prompt72PanelBorder();
		else if (reminderId == 73) return config.prompt73PanelBorder();
		else if (reminderId == 74) return config.prompt74PanelBorder();
		else if (reminderId == 75) return config.prompt75PanelBorder();
		else if (reminderId == 76) return config.prompt76PanelBorder();
		else if (reminderId == 77) return config.prompt77PanelBorder();
		else if (reminderId == 78) return config.prompt78PanelBorder();
		else if (reminderId == 79) return config.prompt79PanelBorder();
		else if (reminderId == 80) return config.prompt80PanelBorder();
		else if (reminderId == 81) return config.prompt81PanelBorder();
		else if (reminderId == 82) return config.prompt82PanelBorder();
		else if (reminderId == 83) return config.prompt83PanelBorder();
		else if (reminderId == 84) return config.prompt84PanelBorder();
		else if (reminderId == 85) return config.prompt85PanelBorder();
		else if (reminderId == 86) return config.prompt86PanelBorder();
		else if (reminderId == 87) return config.prompt87PanelBorder();
		else if (reminderId == 88) return config.prompt88PanelBorder();
		else if (reminderId == 89) return config.prompt89PanelBorder();
		else if (reminderId == 90) return config.prompt90PanelBorder();
		else if (reminderId == 91) return config.prompt91PanelBorder();
		else if (reminderId == 92) return config.prompt92PanelBorder();
		else if (reminderId == 93) return config.prompt93PanelBorder();
		else if (reminderId == 94) return config.prompt94PanelBorder();
		else if (reminderId == 95) return config.prompt95PanelBorder();
		else if (reminderId == 96) return config.prompt96PanelBorder();
		else if (reminderId == 97) return config.prompt97PanelBorder();
		else if (reminderId == 98) return config.prompt98PanelBorder();
		else if (reminderId == 99) return config.prompt99PanelBorder();
		else if (reminderId == 100) return config.prompt100PanelBorder();
		else return false;
	}

	boolean isForceShow(int reminderId)
	{
		if (reminderId == 1) return config.prompt1ForceShow();
		else if (reminderId == 2) return config.prompt2ForceShow();
		else if (reminderId == 3) return config.prompt3ForceShow();
		else if (reminderId == 4) return config.prompt4ForceShow();
		else if (reminderId == 5) return config.prompt5ForceShow();
		else if (reminderId == 6) return config.prompt6ForceShow();
		else if (reminderId == 7) return config.prompt7ForceShow();
		else if (reminderId == 8) return config.prompt8ForceShow();
		else if (reminderId == 9) return config.prompt9ForceShow();
		else if (reminderId == 10) return config.prompt10ForceShow();
		else if (reminderId == 11) return config.prompt11ForceShow();
		else if (reminderId == 12) return config.prompt12ForceShow();
		else if (reminderId == 13) return config.prompt13ForceShow();
		else if (reminderId == 14) return config.prompt14ForceShow();
		else if (reminderId == 15) return config.prompt15ForceShow();
		else if (reminderId == 16) return config.prompt16ForceShow();
		else if (reminderId == 17) return config.prompt17ForceShow();
		else if (reminderId == 18) return config.prompt18ForceShow();
		else if (reminderId == 19) return config.prompt19ForceShow();
		else if (reminderId == 20) return config.prompt20ForceShow();
		else if (reminderId == 21) return config.prompt21ForceShow();
		else if (reminderId == 22) return config.prompt22ForceShow();
		else if (reminderId == 23) return config.prompt23ForceShow();
		else if (reminderId == 24) return config.prompt24ForceShow();
		else if (reminderId == 25) return config.prompt25ForceShow();
		else if (reminderId == 26) return config.prompt26ForceShow();
		else if (reminderId == 27) return config.prompt27ForceShow();
		else if (reminderId == 28) return config.prompt28ForceShow();
		else if (reminderId == 29) return config.prompt29ForceShow();
		else if (reminderId == 30) return config.prompt30ForceShow();
		else if (reminderId == 31) return config.prompt31ForceShow();
		else if (reminderId == 32) return config.prompt32ForceShow();
		else if (reminderId == 33) return config.prompt33ForceShow();
		else if (reminderId == 34) return config.prompt34ForceShow();
		else if (reminderId == 35) return config.prompt35ForceShow();
		else if (reminderId == 36) return config.prompt36ForceShow();
		else if (reminderId == 37) return config.prompt37ForceShow();
		else if (reminderId == 38) return config.prompt38ForceShow();
		else if (reminderId == 39) return config.prompt39ForceShow();
		else if (reminderId == 40) return config.prompt40ForceShow();
		else if (reminderId == 41) return config.prompt41ForceShow();
		else if (reminderId == 42) return config.prompt42ForceShow();
		else if (reminderId == 43) return config.prompt43ForceShow();
		else if (reminderId == 44) return config.prompt44ForceShow();
		else if (reminderId == 45) return config.prompt45ForceShow();
		else if (reminderId == 46) return config.prompt46ForceShow();
		else if (reminderId == 47) return config.prompt47ForceShow();
		else if (reminderId == 48) return config.prompt48ForceShow();
		else if (reminderId == 49) return config.prompt49ForceShow();
		else if (reminderId == 50) return config.prompt50ForceShow();
		else if (reminderId == 51) return config.prompt51ForceShow();
		else if (reminderId == 52) return config.prompt52ForceShow();
		else if (reminderId == 53) return config.prompt53ForceShow();
		else if (reminderId == 54) return config.prompt54ForceShow();
		else if (reminderId == 55) return config.prompt55ForceShow();
		else if (reminderId == 56) return config.prompt56ForceShow();
		else if (reminderId == 57) return config.prompt57ForceShow();
		else if (reminderId == 58) return config.prompt58ForceShow();
		else if (reminderId == 59) return config.prompt59ForceShow();
		else if (reminderId == 60) return config.prompt60ForceShow();
		else if (reminderId == 61) return config.prompt61ForceShow();
		else if (reminderId == 62) return config.prompt62ForceShow();
		else if (reminderId == 63) return config.prompt63ForceShow();
		else if (reminderId == 64) return config.prompt64ForceShow();
		else if (reminderId == 65) return config.prompt65ForceShow();
		else if (reminderId == 66) return config.prompt66ForceShow();
		else if (reminderId == 67) return config.prompt67ForceShow();
		else if (reminderId == 68) return config.prompt68ForceShow();
		else if (reminderId == 69) return config.prompt69ForceShow();
		else if (reminderId == 70) return config.prompt70ForceShow();
		else if (reminderId == 71) return config.prompt71ForceShow();
		else if (reminderId == 72) return config.prompt72ForceShow();
		else if (reminderId == 73) return config.prompt73ForceShow();
		else if (reminderId == 74) return config.prompt74ForceShow();
		else if (reminderId == 75) return config.prompt75ForceShow();
		else if (reminderId == 76) return config.prompt76ForceShow();
		else if (reminderId == 77) return config.prompt77ForceShow();
		else if (reminderId == 78) return config.prompt78ForceShow();
		else if (reminderId == 79) return config.prompt79ForceShow();
		else if (reminderId == 80) return config.prompt80ForceShow();
		else if (reminderId == 81) return config.prompt81ForceShow();
		else if (reminderId == 82) return config.prompt82ForceShow();
		else if (reminderId == 83) return config.prompt83ForceShow();
		else if (reminderId == 84) return config.prompt84ForceShow();
		else if (reminderId == 85) return config.prompt85ForceShow();
		else if (reminderId == 86) return config.prompt86ForceShow();
		else if (reminderId == 87) return config.prompt87ForceShow();
		else if (reminderId == 88) return config.prompt88ForceShow();
		else if (reminderId == 89) return config.prompt89ForceShow();
		else if (reminderId == 90) return config.prompt90ForceShow();
		else if (reminderId == 91) return config.prompt91ForceShow();
		else if (reminderId == 92) return config.prompt92ForceShow();
		else if (reminderId == 93) return config.prompt93ForceShow();
		else if (reminderId == 94) return config.prompt94ForceShow();
		else if (reminderId == 95) return config.prompt95ForceShow();
		else if (reminderId == 96) return config.prompt96ForceShow();
		else if (reminderId == 97) return config.prompt97ForceShow();
		else if (reminderId == 98) return config.prompt98ForceShow();
		else if (reminderId == 99) return config.prompt99ForceShow();
		else if (reminderId == 100) return config.prompt100ForceShow();
		else return false;
	}

	int getImageOffset(int reminderId)
	{
		if (reminderId == 1) return config.prompt1ImageOffset();
		else if (reminderId == 2) return config.prompt2ImageOffset();
		else if (reminderId == 3) return config.prompt3ImageOffset();
		else if (reminderId == 4) return config.prompt4ImageOffset();
		else if (reminderId == 5) return config.prompt5ImageOffset();
		else if (reminderId == 6) return config.prompt6ImageOffset();
		else if (reminderId == 7) return config.prompt7ImageOffset();
		else if (reminderId == 8) return config.prompt8ImageOffset();
		else if (reminderId == 9) return config.prompt9ImageOffset();
		else if (reminderId == 10) return config.prompt10ImageOffset();
		else if (reminderId == 11) return config.prompt11ImageOffset();
		else if (reminderId == 12) return config.prompt12ImageOffset();
		else if (reminderId == 13) return config.prompt13ImageOffset();
		else if (reminderId == 14) return config.prompt14ImageOffset();
		else if (reminderId == 15) return config.prompt15ImageOffset();
		else if (reminderId == 16) return config.prompt16ImageOffset();
		else if (reminderId == 17) return config.prompt17ImageOffset();
		else if (reminderId == 18) return config.prompt18ImageOffset();
		else if (reminderId == 19) return config.prompt19ImageOffset();
		else if (reminderId == 20) return config.prompt20ImageOffset();
		else if (reminderId == 21) return config.prompt21ImageOffset();
		else if (reminderId == 22) return config.prompt22ImageOffset();
		else if (reminderId == 23) return config.prompt23ImageOffset();
		else if (reminderId == 24) return config.prompt24ImageOffset();
		else if (reminderId == 25) return config.prompt25ImageOffset();
		else if (reminderId == 26) return config.prompt26ImageOffset();
		else if (reminderId == 27) return config.prompt27ImageOffset();
		else if (reminderId == 28) return config.prompt28ImageOffset();
		else if (reminderId == 29) return config.prompt29ImageOffset();
		else if (reminderId == 30) return config.prompt30ImageOffset();
		else if (reminderId == 31) return config.prompt31ImageOffset();
		else if (reminderId == 32) return config.prompt32ImageOffset();
		else if (reminderId == 33) return config.prompt33ImageOffset();
		else if (reminderId == 34) return config.prompt34ImageOffset();
		else if (reminderId == 35) return config.prompt35ImageOffset();
		else if (reminderId == 36) return config.prompt36ImageOffset();
		else if (reminderId == 37) return config.prompt37ImageOffset();
		else if (reminderId == 38) return config.prompt38ImageOffset();
		else if (reminderId == 39) return config.prompt39ImageOffset();
		else if (reminderId == 40) return config.prompt40ImageOffset();
		else if (reminderId == 41) return config.prompt41ImageOffset();
		else if (reminderId == 42) return config.prompt42ImageOffset();
		else if (reminderId == 43) return config.prompt43ImageOffset();
		else if (reminderId == 44) return config.prompt44ImageOffset();
		else if (reminderId == 45) return config.prompt45ImageOffset();
		else if (reminderId == 46) return config.prompt46ImageOffset();
		else if (reminderId == 47) return config.prompt47ImageOffset();
		else if (reminderId == 48) return config.prompt48ImageOffset();
		else if (reminderId == 49) return config.prompt49ImageOffset();
		else if (reminderId == 50) return config.prompt50ImageOffset();
		else if (reminderId == 51) return config.prompt51ImageOffset();
		else if (reminderId == 52) return config.prompt52ImageOffset();
		else if (reminderId == 53) return config.prompt53ImageOffset();
		else if (reminderId == 54) return config.prompt54ImageOffset();
		else if (reminderId == 55) return config.prompt55ImageOffset();
		else if (reminderId == 56) return config.prompt56ImageOffset();
		else if (reminderId == 57) return config.prompt57ImageOffset();
		else if (reminderId == 58) return config.prompt58ImageOffset();
		else if (reminderId == 59) return config.prompt59ImageOffset();
		else if (reminderId == 60) return config.prompt60ImageOffset();
		else if (reminderId == 61) return config.prompt61ImageOffset();
		else if (reminderId == 62) return config.prompt62ImageOffset();
		else if (reminderId == 63) return config.prompt63ImageOffset();
		else if (reminderId == 64) return config.prompt64ImageOffset();
		else if (reminderId == 65) return config.prompt65ImageOffset();
		else if (reminderId == 66) return config.prompt66ImageOffset();
		else if (reminderId == 67) return config.prompt67ImageOffset();
		else if (reminderId == 68) return config.prompt68ImageOffset();
		else if (reminderId == 69) return config.prompt69ImageOffset();
		else if (reminderId == 70) return config.prompt70ImageOffset();
		else if (reminderId == 71) return config.prompt71ImageOffset();
		else if (reminderId == 72) return config.prompt72ImageOffset();
		else if (reminderId == 73) return config.prompt73ImageOffset();
		else if (reminderId == 74) return config.prompt74ImageOffset();
		else if (reminderId == 75) return config.prompt75ImageOffset();
		else if (reminderId == 76) return config.prompt76ImageOffset();
		else if (reminderId == 77) return config.prompt77ImageOffset();
		else if (reminderId == 78) return config.prompt78ImageOffset();
		else if (reminderId == 79) return config.prompt79ImageOffset();
		else if (reminderId == 80) return config.prompt80ImageOffset();
		else if (reminderId == 81) return config.prompt81ImageOffset();
		else if (reminderId == 82) return config.prompt82ImageOffset();
		else if (reminderId == 83) return config.prompt83ImageOffset();
		else if (reminderId == 84) return config.prompt84ImageOffset();
		else if (reminderId == 85) return config.prompt85ImageOffset();
		else if (reminderId == 86) return config.prompt86ImageOffset();
		else if (reminderId == 87) return config.prompt87ImageOffset();
		else if (reminderId == 88) return config.prompt88ImageOffset();
		else if (reminderId == 89) return config.prompt89ImageOffset();
		else if (reminderId == 90) return config.prompt90ImageOffset();
		else if (reminderId == 91) return config.prompt91ImageOffset();
		else if (reminderId == 92) return config.prompt92ImageOffset();
		else if (reminderId == 93) return config.prompt93ImageOffset();
		else if (reminderId == 94) return config.prompt94ImageOffset();
		else if (reminderId == 95) return config.prompt95ImageOffset();
		else if (reminderId == 96) return config.prompt96ImageOffset();
		else if (reminderId == 97) return config.prompt97ImageOffset();
		else if (reminderId == 98) return config.prompt98ImageOffset();
		else if (reminderId == 99) return config.prompt99ImageOffset();
		else if (reminderId == 100) return config.prompt100ImageOffset();
		else return 0;
	}

	boolean isImageOffsetNegative(int reminderId)
	{
		if (reminderId == 1) return config.prompt1ImageOffsetNegative();
		else if (reminderId == 2) return config.prompt2ImageOffsetNegative();
		else if (reminderId == 3) return config.prompt3ImageOffsetNegative();
		else if (reminderId == 4) return config.prompt4ImageOffsetNegative();
		else if (reminderId == 5) return config.prompt5ImageOffsetNegative();
		else if (reminderId == 6) return config.prompt6ImageOffsetNegative();
		else if (reminderId == 7) return config.prompt7ImageOffsetNegative();
		else if (reminderId == 8) return config.prompt8ImageOffsetNegative();
		else if (reminderId == 9) return config.prompt9ImageOffsetNegative();
		else if (reminderId == 10) return config.prompt10ImageOffsetNegative();
		else if (reminderId == 11) return config.prompt11ImageOffsetNegative();
		else if (reminderId == 12) return config.prompt12ImageOffsetNegative();
		else if (reminderId == 13) return config.prompt13ImageOffsetNegative();
		else if (reminderId == 14) return config.prompt14ImageOffsetNegative();
		else if (reminderId == 15) return config.prompt15ImageOffsetNegative();
		else if (reminderId == 16) return config.prompt16ImageOffsetNegative();
		else if (reminderId == 17) return config.prompt17ImageOffsetNegative();
		else if (reminderId == 18) return config.prompt18ImageOffsetNegative();
		else if (reminderId == 19) return config.prompt19ImageOffsetNegative();
		else if (reminderId == 20) return config.prompt20ImageOffsetNegative();
		else if (reminderId == 21) return config.prompt21ImageOffsetNegative();
		else if (reminderId == 22) return config.prompt22ImageOffsetNegative();
		else if (reminderId == 23) return config.prompt23ImageOffsetNegative();
		else if (reminderId == 24) return config.prompt24ImageOffsetNegative();
		else if (reminderId == 25) return config.prompt25ImageOffsetNegative();
		else if (reminderId == 26) return config.prompt26ImageOffsetNegative();
		else if (reminderId == 27) return config.prompt27ImageOffsetNegative();
		else if (reminderId == 28) return config.prompt28ImageOffsetNegative();
		else if (reminderId == 29) return config.prompt29ImageOffsetNegative();
		else if (reminderId == 30) return config.prompt30ImageOffsetNegative();
		else if (reminderId == 31) return config.prompt31ImageOffsetNegative();
		else if (reminderId == 32) return config.prompt32ImageOffsetNegative();
		else if (reminderId == 33) return config.prompt33ImageOffsetNegative();
		else if (reminderId == 34) return config.prompt34ImageOffsetNegative();
		else if (reminderId == 35) return config.prompt35ImageOffsetNegative();
		else if (reminderId == 36) return config.prompt36ImageOffsetNegative();
		else if (reminderId == 37) return config.prompt37ImageOffsetNegative();
		else if (reminderId == 38) return config.prompt38ImageOffsetNegative();
		else if (reminderId == 39) return config.prompt39ImageOffsetNegative();
		else if (reminderId == 40) return config.prompt40ImageOffsetNegative();
		else if (reminderId == 41) return config.prompt41ImageOffsetNegative();
		else if (reminderId == 42) return config.prompt42ImageOffsetNegative();
		else if (reminderId == 43) return config.prompt43ImageOffsetNegative();
		else if (reminderId == 44) return config.prompt44ImageOffsetNegative();
		else if (reminderId == 45) return config.prompt45ImageOffsetNegative();
		else if (reminderId == 46) return config.prompt46ImageOffsetNegative();
		else if (reminderId == 47) return config.prompt47ImageOffsetNegative();
		else if (reminderId == 48) return config.prompt48ImageOffsetNegative();
		else if (reminderId == 49) return config.prompt49ImageOffsetNegative();
		else if (reminderId == 50) return config.prompt50ImageOffsetNegative();
		else if (reminderId == 51) return config.prompt51ImageOffsetNegative();
		else if (reminderId == 52) return config.prompt52ImageOffsetNegative();
		else if (reminderId == 53) return config.prompt53ImageOffsetNegative();
		else if (reminderId == 54) return config.prompt54ImageOffsetNegative();
		else if (reminderId == 55) return config.prompt55ImageOffsetNegative();
		else if (reminderId == 56) return config.prompt56ImageOffsetNegative();
		else if (reminderId == 57) return config.prompt57ImageOffsetNegative();
		else if (reminderId == 58) return config.prompt58ImageOffsetNegative();
		else if (reminderId == 59) return config.prompt59ImageOffsetNegative();
		else if (reminderId == 60) return config.prompt60ImageOffsetNegative();
		else if (reminderId == 61) return config.prompt61ImageOffsetNegative();
		else if (reminderId == 62) return config.prompt62ImageOffsetNegative();
		else if (reminderId == 63) return config.prompt63ImageOffsetNegative();
		else if (reminderId == 64) return config.prompt64ImageOffsetNegative();
		else if (reminderId == 65) return config.prompt65ImageOffsetNegative();
		else if (reminderId == 66) return config.prompt66ImageOffsetNegative();
		else if (reminderId == 67) return config.prompt67ImageOffsetNegative();
		else if (reminderId == 68) return config.prompt68ImageOffsetNegative();
		else if (reminderId == 69) return config.prompt69ImageOffsetNegative();
		else if (reminderId == 70) return config.prompt70ImageOffsetNegative();
		else if (reminderId == 71) return config.prompt71ImageOffsetNegative();
		else if (reminderId == 72) return config.prompt72ImageOffsetNegative();
		else if (reminderId == 73) return config.prompt73ImageOffsetNegative();
		else if (reminderId == 74) return config.prompt74ImageOffsetNegative();
		else if (reminderId == 75) return config.prompt75ImageOffsetNegative();
		else if (reminderId == 76) return config.prompt76ImageOffsetNegative();
		else if (reminderId == 77) return config.prompt77ImageOffsetNegative();
		else if (reminderId == 78) return config.prompt78ImageOffsetNegative();
		else if (reminderId == 79) return config.prompt79ImageOffsetNegative();
		else if (reminderId == 80) return config.prompt80ImageOffsetNegative();
		else if (reminderId == 81) return config.prompt81ImageOffsetNegative();
		else if (reminderId == 82) return config.prompt82ImageOffsetNegative();
		else if (reminderId == 83) return config.prompt83ImageOffsetNegative();
		else if (reminderId == 84) return config.prompt84ImageOffsetNegative();
		else if (reminderId == 85) return config.prompt85ImageOffsetNegative();
		else if (reminderId == 86) return config.prompt86ImageOffsetNegative();
		else if (reminderId == 87) return config.prompt87ImageOffsetNegative();
		else if (reminderId == 88) return config.prompt88ImageOffsetNegative();
		else if (reminderId == 89) return config.prompt89ImageOffsetNegative();
		else if (reminderId == 90) return config.prompt90ImageOffsetNegative();
		else if (reminderId == 91) return config.prompt91ImageOffsetNegative();
		else if (reminderId == 92) return config.prompt92ImageOffsetNegative();
		else if (reminderId == 93) return config.prompt93ImageOffsetNegative();
		else if (reminderId == 94) return config.prompt94ImageOffsetNegative();
		else if (reminderId == 95) return config.prompt95ImageOffsetNegative();
		else if (reminderId == 96) return config.prompt96ImageOffsetNegative();
		else if (reminderId == 97) return config.prompt97ImageOffsetNegative();
		else if (reminderId == 98) return config.prompt98ImageOffsetNegative();
		else if (reminderId == 99) return config.prompt99ImageOffsetNegative();
		else if (reminderId == 100) return config.prompt100ImageOffsetNegative();
		else return false;
	}

	Sound getSound(int reminderId)
	{
		if (reminderId == 1) return config.prompt1Sound();
		else if (reminderId == 2) return config.prompt2Sound();
		else if (reminderId == 3) return config.prompt3Sound();
		else if (reminderId == 4) return config.prompt4Sound();
		else if (reminderId == 5) return config.prompt5Sound();
		else if (reminderId == 6) return config.prompt6Sound();
		else if (reminderId == 7) return config.prompt7Sound();
		else if (reminderId == 8) return config.prompt8Sound();
		else if (reminderId == 9) return config.prompt9Sound();
		else if (reminderId == 10) return config.prompt10Sound();
		else if (reminderId == 11) return config.prompt11Sound();
		else if (reminderId == 12) return config.prompt12Sound();
		else if (reminderId == 13) return config.prompt13Sound();
		else if (reminderId == 14) return config.prompt14Sound();
		else if (reminderId == 15) return config.prompt15Sound();
		else if (reminderId == 16) return config.prompt16Sound();
		else if (reminderId == 17) return config.prompt17Sound();
		else if (reminderId == 18) return config.prompt18Sound();
		else if (reminderId == 19) return config.prompt19Sound();
		else if (reminderId == 20) return config.prompt20Sound();
		else if (reminderId == 21) return config.prompt21Sound();
		else if (reminderId == 22) return config.prompt22Sound();
		else if (reminderId == 23) return config.prompt23Sound();
		else if (reminderId == 24) return config.prompt24Sound();
		else if (reminderId == 25) return config.prompt25Sound();
		else if (reminderId == 26) return config.prompt26Sound();
		else if (reminderId == 27) return config.prompt27Sound();
		else if (reminderId == 28) return config.prompt28Sound();
		else if (reminderId == 29) return config.prompt29Sound();
		else if (reminderId == 30) return config.prompt30Sound();
		else if (reminderId == 31) return config.prompt31Sound();
		else if (reminderId == 32) return config.prompt32Sound();
		else if (reminderId == 33) return config.prompt33Sound();
		else if (reminderId == 34) return config.prompt34Sound();
		else if (reminderId == 35) return config.prompt35Sound();
		else if (reminderId == 36) return config.prompt36Sound();
		else if (reminderId == 37) return config.prompt37Sound();
		else if (reminderId == 38) return config.prompt38Sound();
		else if (reminderId == 39) return config.prompt39Sound();
		else if (reminderId == 40) return config.prompt40Sound();
		else if (reminderId == 41) return config.prompt41Sound();
		else if (reminderId == 42) return config.prompt42Sound();
		else if (reminderId == 43) return config.prompt43Sound();
		else if (reminderId == 44) return config.prompt44Sound();
		else if (reminderId == 45) return config.prompt45Sound();
		else if (reminderId == 46) return config.prompt46Sound();
		else if (reminderId == 47) return config.prompt47Sound();
		else if (reminderId == 48) return config.prompt48Sound();
		else if (reminderId == 49) return config.prompt49Sound();
		else if (reminderId == 50) return config.prompt50Sound();
		else if (reminderId == 51) return config.prompt51Sound();
		else if (reminderId == 52) return config.prompt52Sound();
		else if (reminderId == 53) return config.prompt53Sound();
		else if (reminderId == 54) return config.prompt54Sound();
		else if (reminderId == 55) return config.prompt55Sound();
		else if (reminderId == 56) return config.prompt56Sound();
		else if (reminderId == 57) return config.prompt57Sound();
		else if (reminderId == 58) return config.prompt58Sound();
		else if (reminderId == 59) return config.prompt59Sound();
		else if (reminderId == 60) return config.prompt60Sound();
		else if (reminderId == 61) return config.prompt61Sound();
		else if (reminderId == 62) return config.prompt62Sound();
		else if (reminderId == 63) return config.prompt63Sound();
		else if (reminderId == 64) return config.prompt64Sound();
		else if (reminderId == 65) return config.prompt65Sound();
		else if (reminderId == 66) return config.prompt66Sound();
		else if (reminderId == 67) return config.prompt67Sound();
		else if (reminderId == 68) return config.prompt68Sound();
		else if (reminderId == 69) return config.prompt69Sound();
		else if (reminderId == 70) return config.prompt70Sound();
		else if (reminderId == 71) return config.prompt71Sound();
		else if (reminderId == 72) return config.prompt72Sound();
		else if (reminderId == 73) return config.prompt73Sound();
		else if (reminderId == 74) return config.prompt74Sound();
		else if (reminderId == 75) return config.prompt75Sound();
		else if (reminderId == 76) return config.prompt76Sound();
		else if (reminderId == 77) return config.prompt77Sound();
		else if (reminderId == 78) return config.prompt78Sound();
		else if (reminderId == 79) return config.prompt79Sound();
		else if (reminderId == 80) return config.prompt80Sound();
		else if (reminderId == 81) return config.prompt81Sound();
		else if (reminderId == 82) return config.prompt82Sound();
		else if (reminderId == 83) return config.prompt83Sound();
		else if (reminderId == 84) return config.prompt84Sound();
		else if (reminderId == 85) return config.prompt85Sound();
		else if (reminderId == 86) return config.prompt86Sound();
		else if (reminderId == 87) return config.prompt87Sound();
		else if (reminderId == 88) return config.prompt88Sound();
		else if (reminderId == 89) return config.prompt89Sound();
		else if (reminderId == 90) return config.prompt90Sound();
		else if (reminderId == 91) return config.prompt91Sound();
		else if (reminderId == 92) return config.prompt92Sound();
		else if (reminderId == 93) return config.prompt93Sound();
		else if (reminderId == 94) return config.prompt94Sound();
		else if (reminderId == 95) return config.prompt95Sound();
		else if (reminderId == 96) return config.prompt96Sound();
		else if (reminderId == 97) return config.prompt97Sound();
		else if (reminderId == 98) return config.prompt98Sound();
		else if (reminderId == 99) return config.prompt99Sound();
		else if (reminderId == 100) return config.prompt100Sound();
		else return Sound.NONE;
	}
}