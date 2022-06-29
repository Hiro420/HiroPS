package emu.grasscutter.game.managers.chat;

import emu.grasscutter.GameConstants;
import emu.grasscutter.command.CommandMap;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.ChatInfoOuterClass.ChatInfo;
import emu.grasscutter.server.game.GameServer;
import emu.grasscutter.server.packet.send.PacketPlayerChatNotify;
import emu.grasscutter.server.packet.send.PacketPrivateChatNotify;
import emu.grasscutter.server.packet.send.PacketPullPrivateChatRsp;
import emu.grasscutter.server.packet.send.PacketPullRecentChatRsp;
import emu.grasscutter.utils.Utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static emu.grasscutter.Configuration.*;

public class ChatManager implements ChatManagerHandler {
	static final List<Character> PREFIXES = Arrays.asList('/', '!');

	// We store the chat history for ongoing sessions in the form
	//    user id -> chat partner id -> [messages]
	private final Map<Integer, Map<Integer, List<ChatInfo>>> history = new HashMap<>();

	private final GameServer server;
	
	public ChatManager(GameServer server) {
		this.server = server;
	}

	public GameServer getServer() {
		return server;
	}

	/********************
	 * Chat history handling
	 ********************/
	private void putInHistory(int uid, int targetId, ChatInfo info) {
		if (!this.history.containsKey(uid)) {
			this.history.put(uid, new HashMap<>());
		}
		if (!this.history.get(uid).containsKey(targetId)) {
			this.history.get(uid).put(targetId, new ArrayList<>());
		}

		this.history.get(uid).get(targetId).add(info);
	}

	public void clearHistoryOnLogout(Player player) {
		if (this.history.containsKey(player.getUid())) {
			this.history.remove(player.getUid());
		}
	}

	public void handlePullPrivateChatReq(Player player, int targetUid) {
		if (this.history.containsKey(player.getUid()) && this.history.get(player.getUid()).containsKey(targetUid)) {
			player.sendPacket(new PacketPullPrivateChatRsp(this.history.get(player.getUid()).get(targetUid)));
		}
		else {
			player.sendPacket(new PacketPullPrivateChatRsp(List.of()));
		}
	}

	public void handlePullRecentChatReq(Player player) {
		// For now, we send the list three messages from the server for the recent chat history.
		// This matches the previous behavior, but ultimately, we should probably keep track of the last chat partner
		// for every given player and return the last messages exchanged with that partner.

		// If there is no history for this user with the server yet, create it by sending the welcome messages.
		if (!this.history.containsKey(player.getUid()) || !this.history.get(player.getUid()).containsKey(GameConstants.SERVER_CONSOLE_UID)) {
			this.sendServerWelcomeMessages(player);
		}

		int historyLength = this.history.get(player.getUid()).get(GameConstants.SERVER_CONSOLE_UID).size();
		var messages = this.history.get(player.getUid()).get(GameConstants.SERVER_CONSOLE_UID).subList(Math.max(historyLength - 3, 0), historyLength);
		player.sendPacket(new PacketPullRecentChatRsp(messages));
	}

	/********************
	 * Sending messages
	 ********************/
	public void sendPrivateMessageFromServer(int targetUid, String message) {
		// Sanity checks.
		if (message == null || message.length() == 0) {
			return;
		}
		
		// Get target.
		Player target = getServer().getPlayerByUid(targetUid);
		if (target == null) {
			return;
		}

		// Create chat packet and put in history.
		var packet = new PacketPrivateChatNotify(GameConstants.SERVER_CONSOLE_UID, targetUid, message);
		putInHistory(targetUid, GameConstants.SERVER_CONSOLE_UID, packet.getChatInfo());

		// Send.
		target.sendPacket(packet);
	}
	public void sendPrivateMessageFromServer(int targetUid, int emote) {
		// Get target.
		Player target = getServer().getPlayerByUid(targetUid);
		if (target == null) {
			return;
		}

		// Create chat packet and put in history.
		var packet = new PacketPrivateChatNotify(GameConstants.SERVER_CONSOLE_UID, targetUid, emote);
		putInHistory(targetUid, GameConstants.SERVER_CONSOLE_UID, packet.getChatInfo());

		// Send.
		target.sendPacket(packet);
	}

	public void sendPrivateMessage(Player player, int targetUid, String message) {
		// Sanity checks.
		if (message == null || message.length() == 0) {
			return;
		}
		
		// Get target.
		Player target = getServer().getPlayerByUid(targetUid);

		if (target == null && targetUid != GameConstants.SERVER_CONSOLE_UID) {
			return;
		}
				
		// Check if command.
		if (PREFIXES.contains(message.charAt(0))) {
			CommandMap.getInstance().invoke(player, target, message.substring(1));
			return;
		}

		// Create chat packet.
		var packet = new PacketPrivateChatNotify(player.getUid(), targetUid, message);

		// Send and put in history.
		player.sendPacket(packet);
		putInHistory(player.getUid(), targetUid, packet.getChatInfo());

		if (target != null) {
			target.sendPacket(packet);
			putInHistory(targetUid, player.getUid(), packet.getChatInfo());
		}
	}
	public void sendPrivateMessage(Player player, int targetUid, int emote) {
		// Get target.
		Player target = getServer().getPlayerByUid(targetUid);
		
		if (target == null && targetUid != GameConstants.SERVER_CONSOLE_UID) {
			return;
		}
		
		// Create chat packet.
		var packet = new PacketPrivateChatNotify(player.getUid(), target.getUid(), emote);

		// Send and put is history.
		player.sendPacket(packet);
		putInHistory(player.getUid(), targetUid, packet.getChatInfo());

		if (target != null) {
			target.sendPacket(packet);
			putInHistory(targetUid, player.getUid(), packet.getChatInfo());
		}
	}
	
	public void sendTeamMessage(Player player, int channel, String message) {
		// Sanity checks
		if (message == null || message.length() == 0) {
			return;
		}
				
		// Check if command
		if (PREFIXES.contains(message.charAt(0))) {
			CommandMap.getInstance().invoke(player, null, message);
			return;
		}

		// Create and send chat packet
		player.getWorld().broadcastPacket(new PacketPlayerChatNotify(player, channel, message));
	}
	public void sendTeamMessage(Player player, int channel, int icon) {
		// Create and send chat packet
		player.getWorld().broadcastPacket(new PacketPlayerChatNotify(player, channel, icon));
	}

	/********************
	 * Welcome messages
	 ********************/
	public void sendServerWelcomeMessages(Player player) {
		var joinOptions = GAME_INFO.joinOptions;
		
		if (joinOptions.welcomeEmotes != null && joinOptions.welcomeEmotes.length > 0) {
			this.sendPrivateMessageFromServer(player.getUid(), joinOptions.welcomeEmotes[Utils.randomRange(0, joinOptions.welcomeEmotes.length - 1)]);
		}
		
		if (joinOptions.welcomeMessage != null && joinOptions.welcomeMessage.length() > 0) {
			this.sendPrivateMessageFromServer(player.getUid(), joinOptions.welcomeMessage);
		}

		this.sendPrivateMessageFromServer(player.getUid(), "THIS IS AN EXPERIMENTAL BUILD OF GRASSCUTTER FOR 2.7.50/2.8\nDON'T LEAK <3");
	}
}
