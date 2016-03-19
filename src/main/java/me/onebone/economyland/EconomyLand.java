package me.onebone.economyland;

/*
 * EconomyLand: A plugin which allows your server to manage lands
 * Copyright (C) 2016  onebone <jyc00410@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.entity.item.EntityItem;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.block.BlockPlaceEvent;
import cn.nukkit.event.inventory.InventoryPickupItemEvent;
import cn.nukkit.event.player.PlayerMoveEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.level.Level;
import cn.nukkit.level.Position;
import cn.nukkit.math.Vector2;
import cn.nukkit.math.Vector3;
import cn.nukkit.network.protocol.UpdateBlockPacket;
import cn.nukkit.network.protocol.UpdateBlockPacket.Entry;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.TextFormat;
import cn.nukkit.utils.Utils;
import me.onebone.economyland.error.LandCountMaximumException;
import me.onebone.economyland.error.LandCreationException;
import me.onebone.economyland.error.LandOverlapException;
import me.onebone.economyland.provider.*;
import me.onebone.economyapi.EconomyAPI;

public class EconomyLand extends PluginBase implements Listener{
	private EconomyAPI api;
	
	private Provider provider;
	
	private Map<Player, Position[]> players;
	private PlayerManager manager;
	private List<Player> removes;
	private Map<String, String> lang;
	
	public int addLand(Position start, Position end, Level level, String owner) throws LandOverlapException, LandCountMaximumException{
		return addLand(start, end, level, owner, 0.0);
	}
	
	public int addLand(Position start, Position end, Level level, String owner, double price) throws LandOverlapException, LandCountMaximumException{
		return addLand(start, end, level, owner, price, new HashMap<String, Object>());
	}
	
	public int addLand(Position start, Position end, Level level, final String owner, double price, Map<String, Object> options) throws LandOverlapException, LandCountMaximumException{
		Land land;
		if((land = this.provider.checkOverlap(start, end)) != null){
			throw new LandOverlapException("Land is overlapping", land);
		}
		
		int max = Integer.MAX_VALUE;
		try{
			max = Integer.parseInt(this.getConfig().get("max-land").toString());
		}catch(NumberFormatException e){}
		
		long count = this.provider.getAll().values().stream().filter((l) -> l.getOwner().toLowerCase().equals(owner)).count();
		if(count >= max){
			throw new LandCountMaximumException("Land is now maximum", max);
		}
		
		return this.provider.addLand(new Vector2(start.x, start.z), new Vector2(end.x, end.z), level, 0, owner);
	}
	
	public String getMessage(String key){
		return this.getMessage(key, new String[]{});
	}
	
	public String getMessage(String key, Object[] params){
		if(this.lang.containsKey(key)){
			return replaceMessage(this.lang.get(key), params);
		}
		return "Could not find message with " + key;
	}
	
	private String replaceMessage(String lang, Object[] params){
		StringBuilder builder = new StringBuilder();
		
		for(int i = 0; i < lang.length(); i++){
			char c = lang.charAt(i);
			if(c == '{'){
				int index;
				if((index = lang.indexOf('}', i)) != -1){
					try{
						String p = lang.substring(i + 1, index);
						if(p.equals("M")){
							i = index;
							
							builder.append(api.getMonetaryUnit());
							continue;
						}
						int param = Integer.parseInt(p);
						
						if(params.length > param){
							i = index;
							
							builder.append(params[param]);
							continue;
						}
					}catch(NumberFormatException e){}
				}
			}else if(c == '&'){
				char color = lang.charAt(++i);
				if((color >= '0' && color <= 'f') || color == 'r' || color == 'l' || color == 'o'){
					builder.append(TextFormat.ESCAPE);
					builder.append(color);
					continue;
				}
			}
			
			builder.append(c);
		}
		
		return builder.toString();
	}
	
	@Override
	public void onEnable(){
		this.saveDefaultConfig();
		
		players = new HashMap<>();
		removes = new ArrayList<Player>();
		
		manager = new PlayerManager();
		
		api = EconomyAPI.getInstance();
		
		InputStream is = this.getResource("lang_" + this.getConfig().get("langauge", "eng") + ".json");
		if(is == null){
			this.getLogger().critical("Could not load language file. Changing to default.");
			
			is = this.getResource("lang_eng.json");
		}
		
		try{
			lang = new GsonBuilder().create().fromJson(Utils.readFile(is), new TypeToken<LinkedHashMap<String, String>>(){}.getType());
		}catch(JsonSyntaxException | IOException e){
			this.getLogger().critical(e.getMessage());
		}
		
		this.provider = new YamlProvider(this, new File(this.getDataFolder(), "Land.yml"));
		
		this.getServer().getScheduler().scheduleDelayedRepeatingTask(new ShowBlockTask(this), 20, 20);
		this.getServer().getPluginManager().registerEvents(this, this);
	}
	
	@Override
	public void onDisable(){
		if(this.provider != null){
			this.provider.close();
		}
	}
	
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args){
		if(command.getName().equals("land")){
			if(args.length < 1){
				sender.sendMessage(TextFormat.RED + "Usage: " + command.getUsage());
				return true;
			}
			
			args[0] = args[0].toLowerCase();
			
			if(args[0].equals("pos1")){
				if(!(sender instanceof Player)){
					sender.sendMessage(TextFormat.RED + "Please run this command in-game.");
					return true;
				}
				
				Player player = (Player) sender;
				if(!player.hasPermission("economyland.command.land.pos1")){
					player.sendMessage(TextFormat.RED + "You don't have permission to use this command.");
					return true;
				}
				
				if(!players.containsKey(player)){
					players.put(player, new Position[2]);
				}
				players.get(player)[0] = new Position(player.x, player.y, player.z, player.level);
				
				sender.sendMessage(this.getMessage("pos1-set", new Object[]{
						(int) player.x, (int) player.y, (int) player.z
				}));
			}else if(args[0].equals("pos2")){
				if(!(sender instanceof Player)){
					sender.sendMessage(TextFormat.RED + "Please run this command in-game.");
					return true;
				}
				
				Player player = (Player) sender;
				if(!player.hasPermission("economyland.command.land.pos2")){
					player.sendMessage(TextFormat.RED + "You don't have permission to use this command.");
					return true;
				}
				
				if(!players.containsKey(player)){
					sender.sendMessage(this.getMessage("pos1-not-set"));
					return true;
				}
				
				Position pos1 = players.get(player)[0];
				if(pos1.level != player.level){
					sender.sendMessage(this.getMessage("must-one-world"));
					return true;
				}
				players.get(player)[1] = new Position(player.x, player.y, player.z, player.level);

				double price = (Math.abs(Math.floor(player.x) - Math.floor(pos1.x)) + 1) * (Math.abs(Math.floor(player.y) - Math.floor(pos1.y)) + 1) * this.getConfig().getDouble("price.per-block", 100D);
				
				sender.sendMessage(this.getMessage("pos2-set", new Object[]{
						(int) player.x, (int) player.y, (int) player.z, price
				}));
			}else if(args[0].equals("buy")){
				if(!(sender instanceof Player)){
					sender.sendMessage(TextFormat.RED + "Please run this command in-game.");
					return true;
				}
				
				Player player = (Player) sender;
				if(!player.hasPermission("economyland.command.land.buy")){
					player.sendMessage(TextFormat.RED + "You don't have permission to use this command.");
					return true;
				}
				
				if(!players.containsKey(player)){
					sender.sendMessage(this.getMessage("pos-not-set"));
					return true;
				}
				
				Position pos1 = players.get(player)[0];
				Position pos2 = players.get(player)[1];
				
				if(pos1 == null || pos2 == null || pos1.level != pos2.level){
					sender.sendMessage(this.getMessage("pos-not-set"));
					return true;
				}
				
				double price = (Math.abs(Math.floor(player.x) - Math.floor(pos1.x)) + 1) * (Math.abs(Math.floor(player.y) - Math.floor(pos1.y)) + 1) * this.getConfig().getDouble("price.per-block", 100D);
				if(this.api.myMoney(player) >= price){
					try{
						this.addLand(pos1, pos2, pos1.level, player.getName());
						this.api.reduceMoney(player, price, true);
						
						sender.sendMessage(this.getMessage("bought-land"));
					}catch(LandOverlapException e){
						sender.sendMessage(this.getMessage("land-overlap", new Object[]{
							e.overlappingWith().getId(), e.overlappingWith().getOwner()
						}));
					}catch(LandCountMaximumException e){
						sender.sendMessage(this.getMessage("max-land-count", new Object[]{e.getMax()}));
					}
				}else{
					sender.sendMessage(this.getMessage("no-money"));
				}
				
				removes.add(player);
			}else if(args[0].equals("sell")){
				if(!sender.hasPermission("economyland.command.land.sell")){
					sender.sendMessage(TextFormat.RED + "You don't have permission to use this command.");
					return true;
				}
				
				if(args.length < 2){
					sender.sendMessage(TextFormat.RED + "Usage: " + command.getUsage());
					return true;
				}
				
				int id;
				try{
					id = Integer.parseInt(args[1]);
				}catch(NumberFormatException e){
					sender.sendMessage(this.getMessage("invalid-land-id", new Object[]{args[1]}));
					return true;
				}
				
				Land land = this.provider.getLand(id);
				if(land == null){
					sender.sendMessage(this.getMessage("no-such-land", new Object[]{id}));
					return true;
				}
				
				if(land.getOwner().toLowerCase().equals(sender.getName().toLowerCase()) || sender.hasPermission("economyland.admin.sell")){
					this.provider.removeLand(land.getId());
					
					this.api.addMoney(land.getOwner(), land.getPrice() / 2);
					
					sender.sendMessage(this.getMessage("sold-land", new Object[]{id, land.getPrice() / 2}));
				}else{
					sender.sendMessage(this.getMessage("not-your-land", new Object[]{id}));
				}
			}else if(args[0].equals("here")){
				if(!(sender instanceof Player)){
					sender.sendMessage(TextFormat.RED + "Please run this command in-game.");
					return true;
				}
				
				Player player = (Player) sender;
				if(!player.hasPermission("economyland.command.land.here")){
					player.sendMessage(TextFormat.RED + "You don't have permission to use this command.");
					return true;
				}
				
				Land land = this.provider.findLand(player);
				if(land == null){
					player.sendMessage(this.getMessage("no-land-here"));
				}else{
					player.sendMessage(this.getMessage("land-info", new Object[]{
						land.getId(), land.getWidth(), land.getOwner()
					}));
				}
			}else if(args[0].equals("give")){
				if(!(sender instanceof Player)){
					sender.sendMessage(TextFormat.RED + "Please run this command in-game.");
					return true;
				}
				
				if(!sender.hasPermission("economyland.command.land.sell")){
					sender.sendMessage(TextFormat.RED + "You don't have permission to use this command.");
					return true;
				}
				
				if(args.length < 3){
					sender.sendMessage(TextFormat.RED + "Usage: " + command.getUsage());
					return true;
				}
				
				int id;
				try{
					id = Integer.parseInt(args[1]);
				}catch(NumberFormatException e){
					sender.sendMessage(this.getMessage("invalid-land-id", new Object[]{args[1]}));
					return true;
				}
				
				Player player;
				if((player = this.getServer().getPlayer(args[2])) == null){
					sender.sendMessage(this.getMessage("player-not-online", new Object[]{args[2]}));
					return true;
				}
				
				Land land = this.provider.getLand(id);
				if(land == null){
					sender.sendMessage(this.getMessage("no-such-land", new Object[]{id}));
					return true;
				}
				if(land.getOwner().toLowerCase().equals(sender.getName().toLowerCase()) || sender.hasPermission("economyland.admin.give")){
					this.provider.setOwner(id, player.getName());
					
					sender.sendMessage(this.getMessage("owner-changed", new Object[]{id, land.getOwner()}));
				}else{
					sender.sendMessage(this.getMessage("not-your-land", new Object[]{land.getId()}));
				}
			}else if(args[0].equals("whose")){
				if(!sender.hasPermission("economyland.command.land.whose")){
					sender.sendMessage(TextFormat.RED + "You don't have permission to use this command.");
					return true;
				}
				
				String player = sender instanceof Player ? sender.getName() : "";
				if(args.length > 1){
					player = args[1];
				}
				
				if(player.length() < this.getConfig().get("query-min-length", 3)){
					sender.sendMessage(this.getMessage("query-too-short"));
					return true;
				}
				
				final String query = player.toLowerCase();
				
				StringBuilder builder = new StringBuilder(this.getMessage("whose-header", new Object[]{query}) + "\n");
				this.provider.getAll().values().stream().filter((land) -> !land.getOption("hide", false) && (land.getOwner().toLowerCase().startsWith(query) || land.getOwner().toLowerCase().endsWith(query)))
				.forEach((land) -> {
					builder.append(this.getMessage("land-info", new Object[]{
						land.getId(), land.getWidth(), land.getOwner()
					}) + "\n");
				});
				
				sender.sendMessage(builder.toString());
			}else if(args[0].equals("list")){
				if(!sender.hasPermission("economyland.command.land.list")){
					sender.sendMessage(TextFormat.RED + "You don't have permission to use this command.");
					return true;
				}
				
				int page = 1;
				
				Map<Integer, Land> lands = this.provider.getAll();
				int max = (int) Math.ceil(((double) lands.size()) / 5);
				if(args.length > 1){
					try{
						page = Math.min(max, Math.max(1, Integer.parseInt(args[1])));
					}catch(NumberFormatException e){}
				}
				
				StringBuilder builder = new StringBuilder(this.getMessage("land-list-header", new Object[]{page, max}) + "\n");
				int i = 1;
				
				for(Land land : lands.values()){
					int current = (int)Math.ceil((double)(i++) / 5);
					
					if(current == page){
						builder.append(this.getMessage("land-info", new Object[]{
								land.getId(), land.getWidth(), land.getOwner()
						}) + "\n");
					}else if(current > page) break;
				}
				
				sender.sendMessage(builder.toString());
			}else if(args[0].equals("move")){
				if(!(sender instanceof Player)){
					sender.sendMessage(TextFormat.RED + "Please run this command in-game.");
					return true;
				}
				
				Player player = (Player) sender;
				
				if(!player.hasPermission("economyland.command.land.move")){
					player.sendMessage(TextFormat.RED + "You don't have permission to use this command.");
					return true;
				}
				
				if(args.length < 2){
					sender.sendMessage(TextFormat.RED + "Usage: " + command.getUsage());
					return true;
				}
				
				int id;
				try{
					id = Integer.parseInt(args[1]);
				}catch(NumberFormatException e){
					sender.sendMessage(this.getMessage("invalid-land-id", new Object[]{args[1]}));
					return true;
				}
				
				Land land = this.provider.getLand(id);
				if(land == null){
					sender.sendMessage(this.getMessage("no-such-land", new Object[]{id}));
					return true;
				}
				
				if(!land.hasPermission(player) && !player.hasPermission("economyland.admin.access")){
					sender.sendMessage(this.getMessage("move-forbidden", new Object[]{id}));
					return true;
				}
				
				Vector2 start = land.getStart();
				Vector2 end = land.getEnd();
				
				Vector3 center = new Vector3((start.x + end.x) / 2, 128, (start.y + end.y) / 2);
				player.teleport(player.level.getSafeSpawn(center));
			}else if(args[0].equals("invite")){
				if(!sender.hasPermission("economyland.command.land.invite")){
					sender.sendMessage(TextFormat.RED + "You don't have permission to use this command.");
					return true;
				}
				
				if(args.length < 3){
					sender.sendMessage(TextFormat.RED + "Usage: " + command.getUsage());
					return true;
				}
				
				int id;
				try{
					id = Integer.parseInt(args[1]);
				}catch(NumberFormatException e){
					sender.sendMessage(this.getMessage("invalid-land-id", new Object[]{args[1]}));
					return true;
				}
				
				Land land = this.provider.getLand(id);
				if(land == null){
					sender.sendMessage(this.getMessage("no-such-land", new Object[]{id}));
					return true;
				}
				if(land.getOwner().toLowerCase().equals(sender.getName().toLowerCase()) || sender.hasPermission("economyland.admin.invite")){
					List<String> invitee = this.provider.getInvitee(id);
					if(invitee.contains(args[2].toLowerCase())){
						sender.sendMessage(this.getMessage("already-invitee", new Object[]{args[2], id}));
						return true;
					}
					
					this.provider.addInvitee(id, args[2]);
					sender.sendMessage(this.getMessage("invited-player", new Object[]{args[2], id}));
				}else{
					sender.sendMessage(this.getMessage("not-your-land", new Object[]{id}));
				}
			}else if(args[0].equals("kick")){
				if(!sender.hasPermission("economyland.command.land.kick")){
					sender.sendMessage(TextFormat.RED + "You don't have permission to use this command.");
					return true;
				}
				
				if(args.length < 3){
					sender.sendMessage(TextFormat.RED + "Usage: " + command.getUsage());
					return true;
				}
				
				int id;
				try{
					id = Integer.parseInt(args[1]);
				}catch(NumberFormatException e){
					sender.sendMessage(this.getMessage("invalid-land-id", new Object[]{args[1]}));
					return true;
				}
				
				Land land = this.provider.getLand(id);
				if(land == null){
					sender.sendMessage(this.getMessage("no-such-land", new Object[]{id}));
					return true;
				}
				if(land.getOwner().toLowerCase().equals(sender.getName().toLowerCase()) || sender.hasPermission("economyland.admin.kick")){
					List<String> invitee = this.provider.getInvitee(id);
					if(invitee.contains(args[2].toLowerCase())){
						this.provider.removeInvitee(id, args[2]);
						sender.sendMessage(this.getMessage("kicked-invitee", new Object[]{args[2], id}));
						return true;
					}
					sender.sendMessage(this.getMessage("not-invitee", new Object[]{args[2], id}));
				}else{
					sender.sendMessage(this.getMessage("not-your-land", new Object[]{id}));
				}
			}else if(args[0].equals("invitee")){
				if(!sender.hasPermission("economyland.command.land.invitee")){
					sender.sendMessage(TextFormat.RED + "You don't have permission to use this command.");
					return true;
				}
				
				if(args.length < 2){
					sender.sendMessage(TextFormat.RED + "Usage: " + command.getUsage());
					return true;
				}
				
				int id;
				try{
					id = Integer.parseInt(args[1]);
				}catch(NumberFormatException e){
					sender.sendMessage(this.getMessage("invalid-land-id", new Object[]{args[1]}));
					return true;
				}
				
				Land land = this.provider.getLand(id);
				if(land == null){
					sender.sendMessage(this.getMessage("no-such-land", new Object[]{id}));
					return true;
				}
				
				sender.sendMessage(this.getMessage("invitee-list", new Object[]{id, String.join(", ", land.getInvitee())}));
			}else if(args[0].equals("option")){
				if(!sender.hasPermission("economyland.command.land.option")){
					sender.sendMessage(TextFormat.RED + "You don't have permission to use this command.");
					return true;
				}
				
				if(args.length < 4){
					sender.sendMessage(TextFormat.RED + "Usage: " + command.getUsage());
					return true;
				}
				
				int id;
				try{
					id = Integer.parseInt(args[1]);
				}catch(NumberFormatException e){
					sender.sendMessage(this.getMessage("invalid-land-id", new Object[]{args[1]}));
					return true;
				}
				
				Land land = this.provider.getLand(id);
				if(land == null){
					sender.sendMessage(this.getMessage("no-such-land", new Object[]{id}));
					return true;
				}
				
				if(land.getOwner().toLowerCase().equals(sender.getName().toLowerCase()) || sender.hasPermission("economyland.admin.option")){
					String option = args[2].toLowerCase();
					String value = args[3].toLowerCase();
					
					switch(option){
					case "pvp":
						switch(value){
						case "true":
						case "t":
						case "on":
							this.provider.setOption(id, option, true);
						break;
						case "false":
						case "f":
						case "off":
							this.provider.setOption(id, option, false);
							break;
						default:
							sender.sendMessage(this.getMessage("invalid-option"));
							return true;
						}
						
						sender.sendMessage(this.getMessage("option-set", new Object[]{option, value}));
						return true;
					case "pickup":
						switch(value){
						case "true":
						case "t":
						case "on":
							this.provider.setOption(id, option, true);
						break;
						case "false":
						case "f":
						case "off":
							this.provider.setOption(id, option, false);
							break;
						default:
							sender.sendMessage(this.getMessage("invalid-option"));
							return true;
						}
						return true;
					case "access":
						switch(value){
						case "true":
						case "t":
						case "on":
							this.provider.setOption(id, option, true);
						break;
						case "false":
						case "f":
						case "off":
							this.provider.setOption(id, option, false);
							break;
						default:
							sender.sendMessage(this.getMessage("invalid-option"));
							return true;
						}
						
						sender.sendMessage(this.getMessage("option-set", new Object[]{option, value}));
						return true;
					case "hide":
						switch(value){
						case "true":
						case "t":
						case "on":
							this.provider.setOption(id, option, true);
						break;
						case "false":
						case "f":
						case "off":
							this.provider.setOption(id, option, false);
							break;
						default:
							sender.sendMessage(this.getMessage("invalid-option"));
							return true;
						}
						
						sender.sendMessage(this.getMessage("option-set", new Object[]{option, value}));
						return true;
					}
				}else{
					sender.sendMessage(this.getMessage("not-your-land", new Object[]{id}));
				}
			}else{
				sender.sendMessage(TextFormat.RED + "Usage: " + command.getUsage());
			}
			return true;
		}
		
		return false;
	}
	
	@EventHandler (ignoreCancelled = true)
	public void onBlockBreak(BlockBreakEvent event){
		Player player = event.getPlayer();
		Block block = event.getBlock();
		
		Land land;
		if((land = this.provider.findLand(block)) != null){
			if(!(land.hasPermission(player) || player.hasPermission("economyland.admin.modify"))){
				player.sendMessage(this.getMessage("modify-forbidden"));
				
				event.setCancelled(true);
			}
		}else if(this.getConfig().getStringList("white-world-protection").contains(block.level.getFolderName()) || player.hasPermission("economyland.admin.modify")){
			player.sendMessage(this.getMessage("modify-whiteland"));
			
			event.setCancelled();
		}
	}
	
	@EventHandler (ignoreCancelled = true)
	public void onBlockPlace(BlockPlaceEvent event){
		Player player = event.getPlayer();
		Block block = event.getBlockReplace();
		
		Land land;
		if((land = this.provider.findLand(block)) != null){
			if(!(land.hasPermission(player) || player.hasPermission("economyland.admin.modify"))){
				event.setCancelled(true);
				
				player.sendMessage(this.getMessage("modify-forbidden"));
			}
		}else if(this.getConfig().getStringList("white-world-protection").contains(block.level.getFolderName()) || player.hasPermission("economyland.admin.modify")){
			event.setCancelled(true);
			
			player.sendMessage(this.getMessage("modify-whiteland"));
		}
	}
	
	@EventHandler (ignoreCancelled = true)
	public void onItemPickup(InventoryPickupItemEvent event){
		if(event.getInventory().getHolder() instanceof Player){
			Player player = (Player) event.getInventory().getHolder();
			EntityItem item = event.getItem();
			
			long now = System.currentTimeMillis();
			Long[] lastPickup = this.manager.getLastPickup(player);
			
			if(lastPickup == null || (lastPickup[1] == item.getId() && now - lastPickup[0] > 2000) || lastPickup[1] != item.getId()){
				Land land;
				if((land = this.provider.findLand(item)) != null && !land.getOption("pickup", false)){
					if(!(land.hasPermission(player) || player.hasPermission("economyland.admin.pickup"))){
						event.setCancelled(true);
						player.sendMessage(this.getMessage("pickup-forbidden", new Object[]{
								land.getId(), land.getOwner()
						}));
						
						this.manager.setLastPickup(player, item);
					}
				}
			}else{
				event.setCancelled(true);
			}
		}
	}
	
	@EventHandler (ignoreCancelled = true)
	public void onPlayerMove(PlayerMoveEvent event){
		Player player = event.getPlayer();
		
		if(this.manager.isMoved(player)){
			Land land;
			if((land = this.provider.findLand(player)) != null && !land.getOption("access", false)){
				if(!(land.hasPermission(player) || player.hasPermission("economyland.admin.access"))){
					player.teleport(this.manager.getLastPosition(player));
					
					if(this.manager.canShow(player)){
						player.sendMessage(this.getMessage("access-forbidden", new Object[]{
							land.getId(), land.getOwner()
						}));
						
						this.manager.setShown(player);
					}
					return;
				}
			}
			this.manager.setPosition(player);
		}
	}
	
	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event){
		Player player = event.getPlayer();
		
		this.manager.unsetPlayer(player);
	}
	
	public void showBlocks(boolean show){
		UpdateBlockPacket pk = new UpdateBlockPacket();
		
		for(Player player : players.keySet()){
			Position[] pos = players.get(player);
			
			if(player == null) return; // If player is not in server
			
			Position pos1 = pos[0];
			Position pos2 = pos[1];
			
			Entry[] entries = new Entry[1];
			if(pos2 != null){
				entries = new UpdateBlockPacket.Entry[4];
			}
			
			if(pos1 != null){
				if(pos1.level == player.level){
					entries[0] = new Entry((int) pos1.x, (int) pos1.z, (int) pos1.y, 
							show ? Block.GLASS : player.level.getBlock(pos1).getId(), 0, UpdateBlockPacket.FLAG_ALL);
					
					if(pos2 != null){
						entries[1] = new Entry((int) pos2.x, (int) pos2.z, (int) pos2.y, 
								show ? Block.GLASS : player.level.getBlock(pos1).getId(), 0, UpdateBlockPacket.FLAG_ALL);
						entries[2] = new Entry((int) pos1.x, (int) pos2.z, (int) pos1.y, 
								show ? Block.GLASS : player.level.getBlock(pos1).getId(), 0, UpdateBlockPacket.FLAG_ALL);
						entries[3] = new Entry((int) pos2.x, (int) pos1.z, (int) pos1.y, 
								show ? Block.GLASS : player.level.getBlock(pos1).getId(), 0, UpdateBlockPacket.FLAG_ALL);
					}
					pk.records = entries;
				}
				
				player.dataPacket(pk);
			}
			
			if(!show && removes.contains(player)){
				players.remove(player);
				removes.remove(player);
			}
		}
	}
}
