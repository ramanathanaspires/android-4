package com.irccloud.android;

import java.util.ArrayList;

import android.app.Activity;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.actionbarsherlock.app.SherlockListFragment;

public class UsersListFragment extends SherlockListFragment {
	private static final int TYPE_HEADING = 0;
	private static final int TYPE_USER = 1;
	
	NetworkConnection conn;
	UserListAdapter adapter;
	OnUserSelectedListener mListener;
	int cid;
	String channel;
	
	private class UserListAdapter extends BaseAdapter {
		ArrayList<UserListEntry> data;
		private SherlockListFragment ctx;
		
		private class ViewHolder {
			int type;
			TextView label;
			TextView count;
		}
	
		private class UserListEntry {
			int type;
			String text;
			int count;
		}

		public UserListAdapter(SherlockListFragment context) {
			ctx = context;
			data = new ArrayList<UserListEntry>();
		}
		
		public void setItems(ArrayList<UserListEntry> items) {
			data = items;
		}
		
		public UserListEntry buildItem(int type, String text, int count) {
			UserListEntry e = new UserListEntry();
			e.type = type;
			e.text = text;
			e.count = count;
			return e;
		}
		
		@Override
		public int getCount() {
			return data.size();
		}

		@Override
		public Object getItem(int position) {
			return data.get(position);
		}

		@Override
		public long getItemId(int position) {
			return position;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			UserListEntry e = data.get(position);
			View row = convertView;
			ViewHolder holder;

			if(row != null && ((ViewHolder)row.getTag()).type != e.type)
				row = null;
			
			if (row == null) {
				LayoutInflater inflater = ctx.getLayoutInflater(null);
				if(e.type == TYPE_HEADING)
					row = inflater.inflate(R.layout.row_usergroup, null);
				else
					row = inflater.inflate(R.layout.row_user, null);

				holder = new ViewHolder();
				holder.label = (TextView) row.findViewById(R.id.label);
				holder.count = (TextView) row.findViewById(R.id.count);
				holder.type = e.type;

				row.setTag(holder);
			} else {
				holder = (ViewHolder) row.getTag();
			}

			holder.label.setText(e.text);
			
			if(e.type == TYPE_USER && e.count > 0) {
				holder.label.setTextColor(0xFFAAAAAA);
			}
			
			if(holder.count != null) {
				if(e.count > 0) {
					holder.count.setVisibility(View.VISIBLE);
					holder.count.setText(String.valueOf(e.count));
				} else {
					holder.count.setVisibility(View.GONE);
					holder.count.setText("");
				}
			}
			
			return row;
		}
	}
	
	private class RefreshTask extends AsyncTask<Void, Void, Void> {
		ArrayList<UserListAdapter.UserListEntry> entries = new ArrayList<UserListAdapter.UserListEntry>();
		
		@Override
		protected Void doInBackground(Void... params) {
			ArrayList<UsersDataSource.User> users = UsersDataSource.getInstance().getUsersForChannel(cid, channel);
			ArrayList<UsersDataSource.User> ops = new ArrayList<UsersDataSource.User>();
			ArrayList<UsersDataSource.User> voiced = new ArrayList<UsersDataSource.User>();
			ArrayList<UsersDataSource.User> members = new ArrayList<UsersDataSource.User>();
			if(adapter == null) {
				adapter = new UserListAdapter(UsersListFragment.this);
			}

			Log.i("IRCCloud", "Got " + users.size() + " users");
			
			for(int i = 0; i < users.size(); i++) {
				UsersDataSource.User user = users.get(i);
				if(user.mode.contains("o")) {
					ops.add(user);
				} else if(user.mode.contains("v")) {
					voiced.add(user);
				} else {
					members.add(user);
				}
			}
			
			if(ops.size() > 0) {
				entries.add(adapter.buildItem(TYPE_HEADING, "OPERATORS", ops.size()));
				for(int i = 0; i < ops.size(); i++) {
					UsersDataSource.User user = ops.get(i);
					entries.add(adapter.buildItem(TYPE_USER, user.nick, user.away));
				}
			}
			
			if(voiced.size() > 0) {
				entries.add(adapter.buildItem(TYPE_HEADING, "VOICED", voiced.size()));
				for(int i = 0; i < voiced.size(); i++) {
					UsersDataSource.User user = voiced.get(i);
					entries.add(adapter.buildItem(TYPE_USER, user.nick, user.away));
				}
			}
			
			if(members.size() > 0) {
				entries.add(adapter.buildItem(TYPE_HEADING, "MEMBERS", members.size()));
				for(int i = 0; i < members.size(); i++) {
					UsersDataSource.User user = members.get(i);
					entries.add(adapter.buildItem(TYPE_USER, user.nick, user.away));
				}
			}
			
			return null;
		}
		
		@Override
		protected void onPostExecute(Void result) {
			adapter.setItems(entries);
			
			if(getListAdapter() == null && entries.size() > 0)
				setListAdapter(adapter);
			else
				adapter.notifyDataSetChanged();
		}
	}
	
	@Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }
    
    public void onResume() {
    	super.onResume();
    	conn = NetworkConnection.getInstance();
    	conn.addHandler(mHandler);
    	new RefreshTask().execute((Void)null);
    }
    
    public void onPause() {
    	super.onPause();
    	if(conn != null)
    		conn.removeHandler(mHandler);
    }
    
    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        try {
            mListener = (OnUserSelectedListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString() + " must implement OnUserSelectedListener");
        }
        cid = activity.getIntent().getIntExtra("cid", 0);
        channel = activity.getIntent().getStringExtra("name");
    }
    
    public void onListItemClick(ListView l, View v, int position, long id) {
    	UserListAdapter.UserListEntry e = (UserListAdapter.UserListEntry)adapter.getItem(position);
    	if(e.type == TYPE_USER)
    		mListener.onUserSelected(cid, channel, e.text);
    }
    
	private final Handler mHandler = new Handler() {
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case NetworkConnection.EVENT_JOIN:
			case NetworkConnection.EVENT_PART:
			case NetworkConnection.EVENT_QUIT:
			case NetworkConnection.EVENT_NICKCHANGE:
		    	new RefreshTask().execute((Void)null);
				break;
			default:
				break;
			}
		}
	};
	
	public interface OnUserSelectedListener {
		public void onUserSelected(int cid, String channel, String name);
	}
}