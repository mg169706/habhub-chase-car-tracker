package com.pexat.habhub.chasecartracker;

import java.util.ArrayList;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.text.format.Time;
import android.widget.Toast;

public class TrackerService extends Service {
	// Notifications
	private NotificationManager notificationManager;

	// Bound Clients
	private ArrayList<Messenger> messengerClients = new ArrayList<Messenger>();
	private final Messenger tsMessenger = new Messenger(new IncomingHandler());

	// Message Types
	public static final int MSG_REGISTER_CLIENT = 1;
	public static final int MSG_DEREGISTER_CLIENT = 2;
	public static final int MSG_GPS_DATA = 3;
	public static final int MSG_SET_CALLSIGN = 3;

	// GPS
	private LocationManager locationManager;
	private LocationListener locationListener;
	private Location location;
	private Time time_lastUpdated = new Time();
	private boolean firstUpdate = false;
	
	// Callsign
	private String callsign;

	/**
	 * Service Setup
	 */

	@Override
	public void onCreate() {
		super.onCreate();

		showNotification();

		locationListener = new MainLocationListener();
		locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
		locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60000, 0, locationListener);
		location = null;

		if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
			locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60000, 0, locationListener);
			Location networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
			if (isBetterLocation(networkLocation, location)) {
				location = networkLocation;
				time_lastUpdated.setToNow();
			}
		}

		if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
			Toast.makeText(getApplicationContext(), "Please enable your GPS!", Toast.LENGTH_LONG).show();
		} else {
			Location GPSLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
			if (isBetterLocation(GPSLocation, location)) {
				location = GPSLocation;
				time_lastUpdated.setToNow();
			}
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		hideNotification();
		locationManager.removeUpdates(locationListener);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		callsign = intent.getStringExtra("callsign") + "_chase";
		return super.onStartCommand(intent, flags, startId);
	}

	/**
	 * GPS
	 */

	private class MainLocationListener implements LocationListener {
		public void onLocationChanged(Location l) {
			// Check if new location has come in too soon
			Time time_current = new Time();
			time_current.setToNow();
			long timeDelta = Long.valueOf(time_current.format("%s")) - Long.valueOf(time_lastUpdated.format("%s"));
			if (timeDelta < 45 && firstUpdate)
				return;

			// Check if location is better than current location
			if (!isBetterLocation(l, location) && firstUpdate)
				return;

			location = l;
			time_lastUpdated.setToNow();
			firstUpdate = true;
			sendDataToUI(location, time_lastUpdated);
			sendDataToHabitat();
		}

		public void onProviderDisabled(String p) {
		}

		public void onProviderEnabled(String p) {
		}

		public void onStatusChanged(String p, int s, Bundle e) {
		}
	}
	
	/**
	 * HTTP
	 */
	
	private void sendDataToHabitat() {
		new Thread(new Runnable() {
			public void run() {
				Time time = new Time();
				time.setToNow();

				ListenerTelemetry telemetry = new ListenerTelemetry(callsign, location, time, AppInfo.getDevice(), AppInfo.getDeviceSoftware(), AppInfo.getApplication(),
						AppInfo.getApplicationVersion(getApplicationContext()));

				HabitatInterface.sendListenerTelemetry(telemetry);
			}
		}).start();
	}

	/**
	 * Service Communications
	 */

	@Override
	public IBinder onBind(Intent intent) {
		return tsMessenger.getBinder();
	}

	private class IncomingHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
				case MSG_REGISTER_CLIENT :
					messengerClients.add(msg.replyTo);
					sendDataToUI(location, time_lastUpdated);
					break;
				case MSG_DEREGISTER_CLIENT :
					messengerClients.remove(msg.replyTo);
					break;
				case MSG_SET_CALLSIGN :
					callsign = msg.getData().getString("callsign") + "_chase";
				default :
					super.handleMessage(msg);
					break;
			}
		}
	}

	private void sendDataToUI(Location l, Time t) {
		if (l == null)
			return;

		for (int i = messengerClients.size() - 1; i >= 0; i--) {
			try {
				Bundle b = new Bundle();
				b.putString("latitude", Double.toString((double) Math.round(l.getLatitude() * 1000000) / 1000000));
				b.putString("longitude", Double.toString((double) Math.round(l.getLongitude() * 1000000) / 1000000));
				b.putString("altitude", Long.toString(Math.round(l.getAltitude())));
				b.putString("speed", Float.toString((float) Math.round(((l.getSpeed() / 1000) * 3600) * 100) / 100));
				b.putString("time", t.format("%H:%M:%S"));
				Message msg = Message.obtain(null, MSG_GPS_DATA);
				msg.setData(b);
				messengerClients.get(i).send(msg);
			} catch (RemoteException e) {
				messengerClients.remove(i);
			}
		}
	}

	/**
	 * Show permanent notification in notifications bar.
	 */

	private void showNotification() {
		notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		Notification n = new Notification(R.drawable.icon, getText(R.string.service_notification), 0);
		PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, Main.class), Notification.FLAG_ONGOING_EVENT);
		n.flags = Notification.FLAG_ONGOING_EVENT;
		n.setLatestEventInfo(this, getText(R.string.service_notification_title), getText(R.string.service_notification), pi);
		notificationManager.notify(R.string.service_notification, n);
	}

	private void hideNotification() {
		notificationManager.cancel(R.string.service_notification);
	}

	/**
	 * GPS Helper Functions
	 */

	private static final int ONE_MINUTE = 1000 * 60;

	/**
	 * Determines whether one Location reading is better than the current
	 * Location fix
	 * 
	 * @param location
	 *            The new Location that you want to evaluate
	 * @param currentBestLocation
	 *            The current Location fix, to which you want to compare the new
	 *            one
	 */
	protected boolean isBetterLocation(Location location, Location currentBestLocation) {
		if (currentBestLocation == null)
			return true;
		if (location == null)
			return false;

		// Check whether the new location fix is newer or older
		long timeDelta = location.getTime() - currentBestLocation.getTime();
		boolean isSignificantlyNewer = timeDelta > ONE_MINUTE;
		boolean isSignificantlyOlder = timeDelta < -ONE_MINUTE;
		boolean isNewer = timeDelta > 0;

		// If it's been more than two minutes since the current location, use
		// the new location because the user has likely moved
		if (isSignificantlyNewer) {
			return true;
			// If the new location is more than two minutes older, it must be
			// worse
		} else if (isSignificantlyOlder) {
			return false;
		}

		// Check whether the new location fix is more or less accurate
		int accuracyDelta = (int) (location.getAccuracy() - currentBestLocation.getAccuracy());
		boolean isLessAccurate = accuracyDelta > 0;
		boolean isMoreAccurate = accuracyDelta < 0;
		boolean isSignificantlyLessAccurate = accuracyDelta > 200;

		// Check if the old and new location are from the same provider
		boolean isFromSameProvider = isSameProvider(location.getProvider(), currentBestLocation.getProvider());

		// Determine location quality using a combination of timeliness and
		// accuracy
		if (isMoreAccurate) {
			return true;
		} else if (isNewer && !isLessAccurate) {
			return true;
		} else if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) {
			return true;
		}
		return false;
	}

	// Checks whether two providers are the same
	private boolean isSameProvider(String provider1, String provider2) {
		if (provider1 == null) {
			return provider2 == null;
		}
		return provider1.equals(provider2);
	}
}
