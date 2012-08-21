package com.irccloud.android;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HttpsURLConnection;

import org.apache.http.message.BasicNameValuePair;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import com.codebutler.android_websockets.WebSocketClient;
import com.irccloud.android.ServersDataSource.Server;

public class NetworkConnection {
	private static final String TAG = "IRCCloud";
	private static NetworkConnection instance = null;

	public static final int STATE_DISCONNECTED = 0;
	public static final int STATE_CONNECTING = 1;
	public static final int STATE_CONNECTED = 2;
	private int state = STATE_DISCONNECTED;

	private WebSocketClient client = null;
	private UserInfo userInfo = null;
	private ArrayList<Handler> handlers = null;
	private String session = null;
	private int last_reqid = 0;
	private Timer shutdownTimer = null;
	private Timer idleTimer = null;
	private long idle_interval = 0;
	
	public static final int EVENT_CONNECTIVITY = 0;
	public static final int EVENT_USERINFO = 1;
	public static final int EVENT_MAKESERVER = 2;
	public static final int EVENT_MAKEBUFFER = 3;
	public static final int EVENT_DELETEBUFFER = 4;
	public static final int EVENT_BUFFERMSG = 5;
	public static final int EVENT_HEARTBEATECHO = 6;
	public static final int EVENT_CHANNELINIT = 7;
	public static final int EVENT_CHANNELTOPIC = 8;
	public static final int EVENT_JOIN = 9;
	public static final int EVENT_PART = 10;
	public static final int EVENT_NICKCHANGE = 11;
	public static final int EVENT_QUIT = 12;
	public static final int EVENT_MEMBERUPDATES = 13;
	public static final int EVENT_USERCHANNELMODE = 14;
	public static final int EVENT_BUFFERARCHIVED = 15;
	public static final int EVENT_BUFFERUNARCHIVED = 16;
	public static final int EVENT_RENAMECONVERSATION = 17;
	public static final int EVENT_STATUSCHANGED = 18;
	public static final int EVENT_CONNECTIONDELETED = 19;
	
	public static final int EVENT_BACKLOG_START = 100;
	public static final int EVENT_BACKLOG_END = 101;
	
	Object parserLock = new Object();
	
	public static NetworkConnection getInstance() {
		if(instance == null) {
			instance = new NetworkConnection();
		}
		return instance;
	}
	
	public int getState() {
		return state;
	}
	
	public void disconnect() {
		if(client!=null)
			client.disconnect();
		if(idleTimer != null) {
			idleTimer.cancel();
			idleTimer = null;
		}
	}
	
	public String login(String email, String password) throws IOException {
		String postdata = "email="+email+"&password="+password;
		String response = doPost(new URL("https://alpha.irccloud.com/chat/login"), postdata);
		try {
			Log.d(TAG, "Result: " + response);
			JSONObject o = new JSONObject(response);
			return o.getString("session");
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return null;
	}
	
	public void connect(String sk) {
		session = sk;
		
		List<BasicNameValuePair> extraHeaders = Arrays.asList(
		    new BasicNameValuePair("Cookie", "session="+session)
		);

		client = new WebSocketClient(URI.create("wss://alpha.irccloud.com"), new WebSocketClient.Listener() {
		    @Override
		    public void onConnect() {
		        Log.d(TAG, "Connected!");
		        state = STATE_CONNECTED;
		        notifyHandlers(EVENT_CONNECTIVITY, null);
		    }

		    @Override
		    public void onMessage(String message) {
		    	if(message.length() > 0) {
					try {
						synchronized(parserLock) {
							parse_object(new JSONObject(message), false);
						}
					} catch (JSONException e) {
						Log.e(TAG, "Unable to parse: " + message);
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
		    	}
		    }

		    @Override
		    public void onMessage(byte[] data) {
		        //Log.d(TAG, String.format("Got binary message! %s", toHexString(data));
		    }

		    @Override
		    public void onDisconnect(int code, String reason) {
		        Log.d(TAG, String.format("Disconnected! Code: %d Reason: %s", code, reason));
		        state = STATE_DISCONNECTED;
		        notifyHandlers(EVENT_CONNECTIVITY, null);
		    }

		    @Override
		    public void onError(Exception error) {
		        Log.e(TAG, "Error!", error);
		    }
		}, extraHeaders);
		
		state = STATE_CONNECTING;
		notifyHandlers(EVENT_CONNECTIVITY, null);
		client.connect();
	}
	
	public int heartbeat(long selected_buffer, int cid, long bid, long last_seen_eid) {
		last_reqid++;
		client.send("{\"_reqid\":"+last_reqid+", \"_method\": \"heartbeat\", \"selectedBuffer\":"+selected_buffer+
				", \"seenEids\":\"{\\\""+cid+"\\\":{\\\""+bid+"\\\":"+last_seen_eid+"}}\"}\n");
		return last_reqid;
	}
	
	public int say(int cid, String to, String message) {
		last_reqid++;
		client.send("{\"_reqid\":"+last_reqid+", \"_method\": \"say\", \"cid\":"+cid+", \"to\":\""+to+"\", \"msg\":\""+message+"\"}\n");
		return last_reqid;
	}
	
	public int join(int cid, String channel, String key) {
		last_reqid++;
		client.send("{\"_reqid\":"+last_reqid+", \"_method\": \"join\", \"cid\":"+cid+", \"channel\":\""+channel+"\", \"key\":\""+key+"\"}\n");
		return last_reqid;
	}
	
	public int part(int cid, String channel, String message) {
		last_reqid++;
		client.send("{\"_reqid\":"+last_reqid+", \"_method\": \"part\", \"cid\":"+cid+", \"channel\":\""+channel+"\", \"msg\":\""+message+"\"}\n");
		return last_reqid;
	}
	
	public int archiveBuffer(int cid, long bid) {
		last_reqid++;
		client.send("{\"_reqid\":"+last_reqid+", \"_method\": \"archive-buffer\", \"cid\":"+cid+", \"id\":"+bid+"}\n");
		return last_reqid;
	}
	
	public int unarchiveBuffer(int cid, long bid) {
		last_reqid++;
		client.send("{\"_reqid\":"+last_reqid+", \"_method\": \"unarchive-buffer\", \"cid\":"+cid+", \"id\":"+bid+"}\n");
		return last_reqid;
	}
	
	public int deleteBuffer(int cid, long bid) {
		last_reqid++;
		client.send("{\"_reqid\":"+last_reqid+", \"_method\": \"delete-buffer\", \"cid\":"+cid+", \"id\":"+bid+"}\n");
		return last_reqid;
	}
	
	public int addServer(String hostname, int port, int ssl, String netname, String nickname, String realname, String server_pass, String nickserv_pass, String joincommands, String channels) {
		last_reqid++;
		client.send("{\"_reqid\":"+last_reqid+", \"_method\": \"add-server\", "+
				"\"hostname\":\""+hostname+"\", "+
				"\"port\":"+port+", "+
				"\"ssl\":"+ssl+", "+
				"\"netname\":\""+netname+"\", "+
				"\"nickname\":\""+nickname+"\", "+
				"\"realname\":\""+realname+"\", "+
				"\"server_pass\":\""+server_pass+"\", "+
				"\"nspass\":\""+nickserv_pass+"\", "+
				"\"joincommands\":\""+joincommands+"\", "+
				"\"channels\":\""+channels+"\""+
				"}\n");
		return last_reqid;
	}
	
	public int editServer(int cid, String hostname, int port, int ssl, String netname, String nickname, String realname, String server_pass, String nickserv_pass, String joincommands) {
		last_reqid++;
		client.send("{\"_reqid\":"+last_reqid+", \"_method\": \"edit-server\", "+
				"\"hostname\":\""+hostname+"\", "+
				"\"port\":"+port+", "+
				"\"ssl\":"+ssl+", "+
				"\"netname\":\""+netname+"\", "+
				"\"nickname\":\""+nickname+"\", "+
				"\"realname\":\""+realname+"\", "+
				"\"server_pass\":\""+server_pass+"\", "+
				"\"nspass\":\""+nickserv_pass+"\", "+
				"\"joincommands\":\""+joincommands+"\", "+
				"\"cid\":"+cid+""+
				"}\n");
		return last_reqid;
	}
	
	public void request_backlog(int cid, long bid, long beforeId) {
		try {
			if(Looper.myLooper() == null)
				Looper.prepare();
			if(beforeId > 0)
				new OOBIncludeTask().execute(new URL("https://alpha.irccloud.com/chat/backlog?cid="+cid+"&bid="+bid+"&beforeid="+beforeId));
			else
				new OOBIncludeTask().execute(new URL("https://alpha.irccloud.com/chat/backlog?cid="+cid+"&bid="+bid));
		} catch (MalformedURLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private void schedule_idle_timer() {
		if(idleTimer != null) {
			idleTimer.cancel();
			idleTimer = null;
		}
		idleTimer = new Timer();

		idleTimer.schedule( new TimerTask(){
             public void run() {
            	 Log.i("IRCCloud", "Websocket idle time exceeded, reconnecting...");
            	 client.disconnect();
            	 client.connect();
                 idleTimer = null;
              }
           }, idle_interval + 10000);
	}
	
	@SuppressWarnings("unchecked")
	private void parse_object(JSONObject object, boolean backlog) throws JSONException {
		//Log.d(TAG, "New event: " + object);
		if(!object.has("type")) { //TODO: This is probably a command response, parse it and send the result back up to the UI!
			Log.d(TAG, "Response: " + object);
			return;
		}
		String type = object.getString("type");
		if(type != null && type.length() > 0) {
			if(type.equalsIgnoreCase("header")) {
				idle_interval = object.getLong("idle_interval");
			} else if(type.equalsIgnoreCase("idle")) {
			} else if(type.equalsIgnoreCase("num_invites")) {
				if(userInfo != null)
					userInfo.num_invites = object.getInt("num_invites");
			} else if(type.equalsIgnoreCase("stat_user")) {
				userInfo = new UserInfo(object);
				notifyHandlers(EVENT_USERINFO, userInfo);
			} else if(type.equalsIgnoreCase("makeserver") || type.equalsIgnoreCase("server_details_changed")) {
				ServersDataSource s = ServersDataSource.getInstance();
				s.deleteServer(object.getInt("cid"));
				ServersDataSource.Server server = s.createServer(object.getInt("cid"), object.getString("name"), object.getString("hostname"),
						object.getInt("port"), object.getString("nick"), object.getString("status"), object.getString("lag").equalsIgnoreCase("undefined")?0:object.getLong("lag"), object.getBoolean("ssl")?1:0,
								object.getString("realname"), object.getString("server_pass"), object.getString("nickserv_pass"), object.getString("join_commands"),
								object.getJSONObject("fail_info").toString(), object.getString("away"));
				if(!backlog)
					notifyHandlers(EVENT_MAKESERVER, server);
			} else if(type.equalsIgnoreCase("connection_deleted")) {
				ServersDataSource s = ServersDataSource.getInstance();
				s.deleteAllDataForServer(object.getInt("cid"));
				if(!backlog)
					notifyHandlers(EVENT_CONNECTIONDELETED, object.getInt("cid"));
			} else if(type.equalsIgnoreCase("makebuffer")) {
				BuffersDataSource b = BuffersDataSource.getInstance();
				ChannelsDataSource c = ChannelsDataSource.getInstance();
				b.deleteBuffer(object.getInt("bid"));
				c.deleteChannel(object.getInt("bid"));
				BuffersDataSource.Buffer buffer = b.createBuffer(object.getInt("bid"), object.getInt("cid"),
						(object.has("min_eid") && !object.getString("min_eid").equalsIgnoreCase("undefined"))?object.getLong("min_eid"):0,
								(object.has("last_seen_eid") && !object.getString("last_seen_eid").equalsIgnoreCase("undefined"))?object.getLong("last_seen_eid"):-1, object.getString("name"), object.getString("buffer_type"), (object.has("archived") && object.getBoolean("archived"))?1:0, (object.has("deferred") && object.getBoolean("deferred"))?1:0);
				if(!backlog)
					notifyHandlers(EVENT_MAKEBUFFER, buffer);
			} else if(type.equalsIgnoreCase("delete_buffer")) {
				BuffersDataSource b = BuffersDataSource.getInstance();
				b.deleteAllDataForBuffer(object.getInt("bid"));
				if(!backlog)
					notifyHandlers(EVENT_DELETEBUFFER, object.getInt("bid"));
			} else if(type.equalsIgnoreCase("buffer_archived")) {
				BuffersDataSource b = BuffersDataSource.getInstance();
				b.updateArchived(object.getInt("bid"), 1);
				if(!backlog)
					notifyHandlers(EVENT_BUFFERARCHIVED, object.getInt("bid"));
			} else if(type.equalsIgnoreCase("buffer_unarchived")) {
				BuffersDataSource b = BuffersDataSource.getInstance();
				b.updateArchived(object.getInt("bid"), 0);
				if(!backlog)
					notifyHandlers(EVENT_BUFFERUNARCHIVED, object.getInt("bid"));
			} else if(type.equalsIgnoreCase("rename_conversation")) {
				BuffersDataSource b = BuffersDataSource.getInstance();
				b.updateName(object.getInt("bid"), object.getString("new_name"));
				if(!backlog)
					notifyHandlers(EVENT_BUFFERUNARCHIVED, object.getInt("bid"));
			} else if(type.equalsIgnoreCase("status_changed")) {
				ServersDataSource s = ServersDataSource.getInstance();
				s.updateStatus(object.getInt("cid"), object.getString("new_status"), object.getJSONObject("fail_info").toString());
				if(!backlog)
					notifyHandlers(EVENT_STATUSCHANGED, object);
			} else if(type.equalsIgnoreCase("buffer_msg") || type.equalsIgnoreCase("buffer_me_msg") || type.equalsIgnoreCase("server_motdstart")
					 || type.equalsIgnoreCase("notice") || type.equalsIgnoreCase("server_welcome") || type.equalsIgnoreCase("server_motd") || type.equalsIgnoreCase("server_endofmotd")
					 || type.equalsIgnoreCase("server_luserclient") || type.equalsIgnoreCase("server_luserop") || type.equalsIgnoreCase("server_luserconns")
					 || type.equalsIgnoreCase("server_luserme") || type.equalsIgnoreCase("server_n_local") || type.equalsIgnoreCase("server_luserchannels")
					 || type.equalsIgnoreCase("server_n_global") || type.equalsIgnoreCase("motd_response") || type.equalsIgnoreCase("server_luserunknown")
					 || type.equalsIgnoreCase("server_yourhost") || type.equalsIgnoreCase("server_created")) {
				EventsDataSource e = EventsDataSource.getInstance();
				e.deleteEvent(object.getLong("eid"), object.getInt("bid"));
				EventsDataSource.Event event = e.createEvent(object.getLong("eid"), object.getInt("bid"), object.getInt("cid"), object.getString("type"), (object.has("highlight") && object.getBoolean("highlight"))?1:0, object);
				if(!backlog)
					notifyHandlers(EVENT_BUFFERMSG, event);
			} else if(type.equalsIgnoreCase("channel_init")) {
				ChannelsDataSource c = ChannelsDataSource.getInstance();
				c.deleteChannel(object.getLong("bid"));
				ChannelsDataSource.Channel channel = c.createChannel(object.getInt("cid"), object.getLong("bid"), object.getString("chan"),
						object.getJSONObject("topic").isNull("text")?"":object.getJSONObject("topic").getString("text"), object.getJSONObject("topic").getLong("time"), 
						object.getJSONObject("topic").getString("nick"), object.getString("channel_type"), object.getString("mode"));
				UsersDataSource u = UsersDataSource.getInstance();
				u.deleteUsersForChannel(object.getInt("cid"), object.getString("chan"));
				JSONArray users = object.getJSONArray("members");
				for(int i = 0; i < users.length(); i++) {
					JSONObject user = users.getJSONObject(i);
					u.createUser(object.getInt("cid"), object.getString("chan"), user.getString("nick"), user.getString("usermask"), user.getString("mode"), user.getBoolean("away")?1:0);
				}
				if(!backlog)
					notifyHandlers(EVENT_CHANNELINIT, channel);
			} else if(type.equalsIgnoreCase("channel_topic")) {
				ChannelsDataSource c = ChannelsDataSource.getInstance();
				c.updateTopic(object.getLong("bid"), object.getString("topic"), object.getLong("eid"), object.getString("author"));
				EventsDataSource e = EventsDataSource.getInstance();
				e.deleteEvent(object.getLong("eid"), object.getInt("bid"));
				EventsDataSource.Event event = e.createEvent(object.getLong("eid"), object.getInt("bid"), object.getInt("cid"), object.getString("type"), (object.has("highlight") && object.getBoolean("highlight"))?1:0, object);
				if(!backlog)
					notifyHandlers(EVENT_CHANNELTOPIC, event);
			} else if(type.equalsIgnoreCase("joined_channel") || type.equalsIgnoreCase("you_joined_channel")) {
				UsersDataSource u = UsersDataSource.getInstance();
				u.deleteUser(object.getInt("cid"), object.getString("chan"), object.getString("nick"));
				u.createUser(object.getInt("cid"), object.getString("chan"), object.getString("nick"), object.getString("hostmask"), "", 0);
				EventsDataSource e = EventsDataSource.getInstance();
				e.deleteEvent(object.getLong("eid"), object.getInt("bid"));
				EventsDataSource.Event event = e.createEvent(object.getLong("eid"), object.getInt("bid"), object.getInt("cid"), object.getString("type"), (object.has("highlight") && object.getBoolean("highlight"))?1:0, object);
				if(!backlog)
					notifyHandlers(EVENT_JOIN, event);
			} else if(type.equalsIgnoreCase("parted_channel") || type.equalsIgnoreCase("you_parted_channel")) {
				UsersDataSource u = UsersDataSource.getInstance();
				u.deleteUser(object.getInt("cid"), object.getString("chan"), object.getString("nick"));
				EventsDataSource e = EventsDataSource.getInstance();
				e.deleteEvent(object.getLong("eid"), object.getInt("bid"));
				EventsDataSource.Event event = e.createEvent(object.getLong("eid"), object.getInt("bid"), object.getInt("cid"), object.getString("type"), (object.has("highlight") && object.getBoolean("highlight"))?1:0, object);
				if(!backlog && type.equalsIgnoreCase("you_parted_channel")) {
					ChannelsDataSource c = ChannelsDataSource.getInstance();
					c.deleteChannel(object.getInt("bid"));
				}
				if(!backlog)
					notifyHandlers(EVENT_PART, event);
			} else if(type.equalsIgnoreCase("quit")) {
				UsersDataSource u = UsersDataSource.getInstance();
				u.deleteUser(object.getInt("cid"), object.getString("chan"), object.getString("nick"));
				EventsDataSource e = EventsDataSource.getInstance();
				e.deleteEvent(object.getLong("eid"), object.getInt("bid"));
				EventsDataSource.Event event = e.createEvent(object.getLong("eid"), object.getInt("bid"), object.getInt("cid"), object.getString("type"), (object.has("highlight") && object.getBoolean("highlight"))?1:0, object);
				if(!backlog)
					notifyHandlers(EVENT_QUIT, event);
			} else if(type.equalsIgnoreCase("nickchange") || type.equalsIgnoreCase("you_nickchange")) {
				if(!DBHelper.getInstance().isBatch()) {
					ChannelsDataSource c = ChannelsDataSource.getInstance();
					ChannelsDataSource.Channel chan = c.getChannelForBuffer(object.getLong("bid"));
					if(chan != null) {
						UsersDataSource u = UsersDataSource.getInstance();
						u.updateNick(object.getInt("cid"), chan.name, object.getString("oldnick"), object.getString("newnick"));
					}
				}
				EventsDataSource e = EventsDataSource.getInstance();
				e.deleteEvent(object.getLong("eid"), object.getInt("bid"));
				EventsDataSource.Event event = e.createEvent(object.getLong("eid"), object.getInt("bid"), object.getInt("cid"), object.getString("type"), (object.has("highlight") && object.getBoolean("highlight"))?1:0, object);
				if(!backlog)
					notifyHandlers(EVENT_NICKCHANGE, event);
			} else if(type.equalsIgnoreCase("user_channel_mode")) {
				if(!DBHelper.getInstance().isBatch()) {
					ChannelsDataSource c = ChannelsDataSource.getInstance();
					ChannelsDataSource.Channel chan = c.getChannelForBuffer(object.getLong("bid"));
					if(chan != null) {
						UsersDataSource u = UsersDataSource.getInstance();
						u.updateMode(object.getInt("cid"), chan.name, object.getString("nick"), object.getString("newmode"));
					}
				}
				EventsDataSource e = EventsDataSource.getInstance();
				e.deleteEvent(object.getLong("eid"), object.getInt("bid"));
				EventsDataSource.Event event = e.createEvent(object.getLong("eid"), object.getInt("bid"), object.getInt("cid"), object.getString("type"), (object.has("highlight") && object.getBoolean("highlight"))?1:0, object);
				if(!backlog)
					notifyHandlers(EVENT_USERCHANNELMODE, event);
			} else if(type.equalsIgnoreCase("member_updates")) {
				JSONObject updates = object.getJSONObject("updates");
				Iterator<String> i = updates.keys();
				while(i.hasNext()) {
					String nick = i.next();
					JSONObject user = updates.getJSONObject(nick);
					if(!DBHelper.getInstance().isBatch()) {
						ChannelsDataSource c = ChannelsDataSource.getInstance();
						ChannelsDataSource.Channel chan = c.getChannelForBuffer(object.getLong("bid"));
						if(chan != null) {
							UsersDataSource u = UsersDataSource.getInstance();
							u.updateAway(object.getInt("cid"), chan.name, user.getString("nick"), user.getBoolean("away")?1:0);
							u.updateHostmask(object.getInt("cid"), chan.name, user.getString("nick"), user.getString("usermask"));
						}
					}
				}
				if(!backlog)
					notifyHandlers(EVENT_MEMBERUPDATES, null);
			} else if(type.equalsIgnoreCase("connection_lag")) {
				ServersDataSource s = ServersDataSource.getInstance();
				s.updateLag(object.getInt("cid"), object.getLong("lag"));
			} else if(type.equalsIgnoreCase("heartbeat_echo")) {
				JSONObject seenEids = object.getJSONObject("seenEids");
				Iterator<String> i = seenEids.keys();
				while(i.hasNext()) {
					String cid = i.next();
					JSONObject eids = seenEids.getJSONObject(cid);
					Iterator<String> j = eids.keys();
					while(j.hasNext()) {
						String bid = j.next();
						long eid = eids.getLong(bid);
						BuffersDataSource.getInstance().updateLastSeenEid(Integer.valueOf(bid), eid);
					}
				}
				if(!backlog)
					notifyHandlers(EVENT_HEARTBEATECHO, null);
			} else if(type.equalsIgnoreCase("oob_include")) {
				try {
					if(Looper.myLooper() == null)
						Looper.prepare();
					new OOBIncludeTask().execute(new URL("https://alpha.irccloud.com" + object.getString("url")));
				} catch (MalformedURLException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			} else {
				//Log.e(TAG, "Unhandled type: " + object);
			}
		}
		if(idle_interval > 0)
			schedule_idle_timer();
	}
	
	private String doGet(URL url) throws IOException {
		Log.d(TAG, "Requesting: " + url);
		
		HttpURLConnection conn = null;

        if (url.getProtocol().toLowerCase().equals("https")) {
            HttpsURLConnection https = (HttpsURLConnection) url.openConnection();
            conn = https;
        } else {
        	conn = (HttpURLConnection) url.openConnection();
        }
		conn.setRequestMethod("GET");
		conn.setRequestProperty("Connection", "close");
		conn.setRequestProperty("Cookie", "session="+session);
		conn.setRequestProperty("Accept", "application/json");
		conn.setRequestProperty("Content-type", "application/json");
		conn.setRequestProperty("Accept-Encoding", "gzip");
		BufferedReader reader = null;
		String response = "";
		conn.connect();
		try {
			if(conn.getInputStream() != null) {
				if(conn.getContentEncoding().equalsIgnoreCase("gzip"))
					reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(conn.getInputStream())), 512);
				else
					reader = new BufferedReader(new InputStreamReader(conn.getInputStream()), 512);
			}
		} catch (IOException e) {
			if(conn.getErrorStream() != null) {
				if(conn.getContentEncoding().equalsIgnoreCase("gzip"))
					reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(conn.getErrorStream())), 512);
				else
					reader = new BufferedReader(new InputStreamReader(conn.getErrorStream()), 512);
			}
		}

		if(reader != null) {
			response = toString(reader);
			reader.close();
		}
		return response;
	}

	private String doPost(URL url, String postdata) throws IOException {
		Log.d(TAG, "POSTing to: " + url);
		
		HttpURLConnection conn = null;

        if (url.getProtocol().toLowerCase().equals("https")) {
            HttpsURLConnection https = (HttpsURLConnection) url.openConnection();
            conn = https;
        } else {
        	conn = (HttpURLConnection) url.openConnection();
        }
		conn.setRequestMethod("POST");
		conn.setDoOutput(true);
		conn.setRequestProperty("Connection", "close");
		conn.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
		OutputStream ostr = null;
		try {
			ostr = conn.getOutputStream();
			ostr.write(postdata.getBytes());
		} finally {
			if (ostr != null)
				ostr.close();
		}

		BufferedReader reader = null;
		String response = "";
		conn.connect();
		try {
			if(conn.getInputStream() != null) {
				reader = new BufferedReader(new InputStreamReader(conn.getInputStream()), 512);
			}
		} catch (IOException e) {
			if(conn.getErrorStream() != null) {
				reader = new BufferedReader(new InputStreamReader(conn.getErrorStream()), 512);
			}
		}

		if(reader != null) {
			response = toString(reader);
			reader.close();
		}
		return response;
	}

	private static String toString(BufferedReader reader) throws IOException {
		StringBuilder sb = new StringBuilder();
		String line;
		while ((line = reader.readLine()) != null) {
			sb.append(line).append('\n');
		}
		return sb.toString();
	}

	public void addHandler(Handler handler) {
		if(handlers == null)
			handlers = new ArrayList<Handler>();
		handlers.add(handler);
		if(shutdownTimer != null) {
			shutdownTimer.cancel();
			shutdownTimer = null;
		}
	}

	public void removeHandler(Handler handler) {
		handlers.remove(handler);
		if(handlers.isEmpty() && shutdownTimer == null) {
			shutdownTimer = new Timer();

			shutdownTimer.schedule( new TimerTask(){
	             public void run() {
	            	 if(handlers.isEmpty()) {
		                 disconnect();
	            	 }
	                 shutdownTimer = null;
	              }
	           }, 5*60000); //TODO: Make this value configurable
		}
	}

	private void notifyHandlers(int message, Object object) {
		for(int i = 0; i < handlers.size(); i++) {
			Handler handler = handlers.get(i);
			Message msg = handler.obtainMessage(message, object);
			handler.sendMessage(msg);
		}
	}
	
	public UserInfo getUserInfo() {
		return userInfo;
	}
	
	public class UserInfo {
		String name;
		String email;
		boolean verified;
		long last_selected_bid;
		long connections;
		long active_connections;
		long join_date;
		boolean auto_away;
		String limits_name;
		long limit_networks;
		boolean limit_passworded_servers;
		long limit_zombiehours;
		boolean limit_download_logs;
		long limit_maxhistorydays;
		int num_invites;
		
		public UserInfo(JSONObject object) throws JSONException {
			name = object.getString("name");
			email = object.getString("email");
			verified = object.getBoolean("verified");
			last_selected_bid = object.getLong("last_selected_bid");
			connections = object.getLong("num_connections");
			active_connections = object.getLong("num_active_connections");
			join_date = object.getLong("join_date");
			auto_away = object.getBoolean("autoaway");
			
			limits_name = object.getString("limits_name");
			JSONObject limits = object.getJSONObject("limits");
			limit_networks = limits.getLong("networks");
			limit_passworded_servers = limits.getBoolean("passworded_servers");
			limit_zombiehours = limits.getLong("zombiehours");
			limit_download_logs = limits.getBoolean("download_logs");
			limit_maxhistorydays = limits.getLong("maxhistorydays");
		}
	}
	
	private class OOBIncludeTask extends AsyncTask<URL, Void, Boolean> {
		String json = null;
		
		@Override
		protected Boolean doInBackground(URL... url) {
			try {
				json = doGet(url[0]);
				synchronized(parserLock) {
					long time = System.currentTimeMillis();
					Log.i("IRCCloud", "Beginning backlog...");
					notifyHandlers(EVENT_BACKLOG_START, null);
					DBHelper.getInstance().beginBatch();
					JSONArray a = new JSONArray(json);
					for(int i = 0; i < a.length(); i++)
						parse_object(a.getJSONObject(i), true);
					DBHelper.getInstance().endBatch();
					Log.i("IRCCloud", "Backlog complete!");
					Log.i("IRCCloud", "Backlog processing took: " + (System.currentTimeMillis() - time) + "ms");
				}
				notifyHandlers(EVENT_BACKLOG_END, null);
				return true;
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			return false;
		}
	}
	
}
