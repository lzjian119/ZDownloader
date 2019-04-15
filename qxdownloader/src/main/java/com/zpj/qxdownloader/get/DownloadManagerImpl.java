package com.zpj.qxdownloader.get;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.google.gson.Gson;
import com.zpj.qxdownloader.io.BufferedRandomAccessFile;
import com.zpj.qxdownloader.option.DefaultOptions;
import com.zpj.qxdownloader.option.MissionOptions;
import com.zpj.qxdownloader.option.QianXunOptions;
import com.zpj.qxdownloader.util.Utility;

import org.jsoup.Connection;
import org.jsoup.Jsoup;

import java.io.File;
import java.net.Proxy;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class DownloadManagerImpl implements DownloadManager
{
	private static final String TAG = DownloadManagerImpl.class.getSimpleName();

	private static String DOWNLOAD_PATH = DefaultOptions.DOWNLOAD_PATH;

	static String TASK_PATH;

	private static DownloadManager mManager;
	
	private Context mContext;

	private DownloadManagerListener downloadManagerListener;

	private QianXunOptions options;


	private DownloadManagerImpl() {

	}

	private DownloadManagerImpl(Context context, QianXunOptions options) {
		mContext = context;
		this.options = options;
		loadMissions();
		TASK_PATH = mContext.getExternalFilesDir("tasks").getAbsolutePath();
		File file = new File(getDownloadPath());
		if (!file.exists()) {
			file.mkdirs();
		}
	}

	public static DownloadManager getInstance() {
		if (mManager == null) {
			throw new RuntimeException("must register first!");
		}
		return mManager;
	}

	public static void register(Context context, QianXunOptions options) {
		if (mManager == null) {
			mManager = new DownloadManagerImpl(context, options);
		}
	}

	public static void setDownloadPath(String downloadPath) {
		DownloadManagerImpl.DOWNLOAD_PATH = downloadPath;
	}

	public static String getDownloadPath() {
		return DOWNLOAD_PATH;
	}

	@Override
	public List<DownloadMission> getMissions() {
		return mMissions;
	}

	@Override
	public void loadMissions() {
		mMissions.clear();
		File f;
		if (TASK_PATH != null) {
			f = new File(TASK_PATH);
		} else {
			f = mContext.getExternalFilesDir("tasks");
		}


		if (f != null) {
			if (f.exists() && f.isDirectory()) {
				File[] subs = f.listFiles();

				for (File sub : subs) {
					if (sub.isDirectory()) {
						continue;
					}

					if (sub.getName().endsWith(".zpj")) {
						String str = Utility.readFromFile(sub.getAbsolutePath());
						if (str != null && !str.trim().equals("")) {

							Log.d(TAG, "loading mission " + sub.getName());
							Log.d(TAG, str);

							DownloadMission mis = new Gson().fromJson(str, DownloadMission.class);

							if (mis.finished) {
								sub.delete();
								continue;
							}

							mis.running = false;
							mis.recovered = true;
							insertMission(mis, false);
						}
					}
				}
			} else {
				f.mkdirs();
			}
		}
		Collections.sort(mMissions, new Comparator<DownloadMission>() {
			@Override
			public int compare(DownloadMission o1, DownloadMission o2) {
				return - (int) (o1.createTime - o2.createTime);
			}
		});
	}

	@Override
	public void setDownloadManagerListener(DownloadManagerListener downloadManagerListener) {
		this.downloadManagerListener = downloadManagerListener;
	}
	
	@Override
	public int startMission(String url, String name, int threads) {
		Log.d("startMission", "开始下载");
		return startMission(url, name, threads, "", "");
	}

	public int startMission(String url) {
		return startMission(url, MissionOptions.with());
	}

	public int startMission(String url, MissionOptions missionOptions) {
		DownloadMission mission = new DownloadMission();
		mission.uuid = UUID.randomUUID().toString();
		mission.createTime = System.currentTimeMillis();
		mission.notifyId = (int)(mission.createTime / 10000) + (int) (mission.createTime % 10000) * 100000;
		mission.url = url;
		mission.originUrl = url;
		mission.location = missionOptions.getDownloadPath();
		mission.cookie = missionOptions.getCookie();
		mission.hasInit = false;
		mission.user_agent = missionOptions.getUserAgent();
		mission.timestamp = System.currentTimeMillis();
		mission.threadCount = missionOptions.getThreadCount();
		int i =  insertMission(mission, false);
		if (downloadManagerListener != null) {
			downloadManagerListener.onMissionAdd();
		}
		mission.writeThisToFile();
		new Initializer(mission).start();
		return i;
	}

	@Override
	public int startMission(String url, String name, int threads, String cookie, String user_agent) {
		DownloadMission mission = new DownloadMission();
		mission.uuid = UUID.randomUUID().toString();
		mission.createTime = System.currentTimeMillis();
		mission.notifyId = (int)(mission.createTime / 10000) + (int) (mission.createTime % 10000) * 100000;
		mission.url = url;
		mission.originUrl = url;
		mission.name = name;
		mission.location = options.getDownloadPath();
		mission.cookie = cookie;
		mission.hasInit = false;
		if (!TextUtils.isEmpty(user_agent)) {
			mission.user_agent = user_agent;
		} else {
			mission.user_agent = System.getProperty("http.agent");
		}
		mission.timestamp = System.currentTimeMillis();
		mission.threadCount = threads;
		int i =  insertMission(mission, false);
		if (downloadManagerListener != null) {
			downloadManagerListener.onMissionAdd();
		}
		mission.writeThisToFile();
		new Initializer(mission).start();
		return i;
	}

	@Override
	public void resumeMission(int i) {
		DownloadMission d = getMission(i);
//		 && d.errCode == -1
		if (!d.running) {
			d.start();
		}
	}

	@Override
	public void resumeMission(String uuid) {
		DownloadMission d = getMission(uuid);
//		 && d.errCode == -1
		if (!d.running) {
			d.start();
		}
	}

	@Override
	public void resumeAllMissions() {
		for (DownloadMission downloadMission : mMissions) {
			if (downloadMission.running) {
				downloadMission.start();
			}
		}
	}

	@Override
	public void pauseMission(int i) {
		DownloadMission d = getMission(i);
		if (d.running) {
			d.pause();
		}
	}

	@Override
	public void pauseMission(String uuid) {
		DownloadMission d = getMission(uuid);
		if (d.running) {
			d.pause();
		}
	}

	@Override
	public void pauseAllMissions() {
		for (DownloadMission downloadMission : mMissions) {
			if (downloadMission.running) {
				downloadMission.pause();
			}
		}
	}
	
	@Override
	public void deleteMission(int i) {
		DownloadMission d = getMission(i);
		d.pause();
		d.delete();
		mMissions.remove(i);
	}

	@Override
	public void deleteMission(String uuid) {
		DownloadMission d = getMission(uuid);
		d.pause();
		d.delete();
		mMissions.remove(d);
	}

	@Override
	public void deleteAllMissions() {
		for (DownloadMission mission : mMissions) {
			mission.pause();
			mission.delete();
		}
		mMissions.clear();
	}

	@Override
	public void clearMission(int i) {
		DownloadMission d = getMission(i);
		d.pause();
		d.deleteThisFromFile();
		mMissions.remove(i);
	}

	@Override
	public void clearMission(String uuid) {
		DownloadMission d = getMission(uuid);
		d.pause();
		d.deleteThisFromFile();
		mMissions.remove(d);
	}

	@Override
	public void clearAllMissions() {
		for (DownloadMission mission : mMissions) {
			mission.pause();
			mission.deleteThisFromFile();
		}
		mMissions.clear();
	}

	@Override
	public DownloadMission getMission(int i) {
		return mMissions.get(i);
	}

	@Override
	public DownloadMission getMission(String uuid) {
		for (DownloadMission mission : mMissions) {
			if (TextUtils.equals(mission.uuid, uuid)) {
				return mission;
			}
		}
		return null;
	}

	@Override
	public int getCount() {
		return mMissions.size();
	}
	
	private int insertMission(DownloadMission mission, boolean isNew) {

		Log.d("insertMission", "insertMission");

		int i = -1;
		
		DownloadMission m = null;
		
		if (mMissions.size() > 0) {
			do {
				m = mMissions.get(++i);
			} while (m.timestamp > mission.timestamp && i < mMissions.size() - 1);
			
			//if (i > 0) i--;
		} else {
			i = 0;
		}

		if (isNew) {
			while (!mission.hasInit) {
				Log.d("insertMission", "wait to insertMission");
			}
		}

		mission.initNotification();
		mMissions.add(i, mission);
		return i;
	}
	
	private class Initializer extends Thread {
//		private Context context;
		private DownloadMission mission;
		
		public Initializer(DownloadMission mission) {
//			this.context = context;
			this.mission = mission;
		}
		
		@Override
		public void run() {
			try {



				Log.d("Initializer", "run");


//				OkHttpClient client = new OkHttpClient();
//				Request request = new Request.Builder()
//						.url(mission.url)
////						.addHeader("User-Agent", UAHelper.getPCBaiduUA())
////						.addHeader("Cookie", UserHelper.getBduss())
////						.addHeader("Accept", "*/*")
////						.addHeader("Referer","https://pan.baidu.com/disk/home")
////						.addHeader("Pragma", "no-cache")
////						.addHeader("Cache-Control", "no-cache")
//						.build();
//
//				Response res = client.newCall(request).execute();
//
//				if (res != null && res.isSuccessful()) {
//					mission.length = res.body().contentLength();
//					Log.d("contentLength", mission.length + "");
//					res.close();
//				}


				Connection.Response response = Jsoup.connect(mission.url)
						.method(Connection.Method.HEAD)
						.followRedirects(false)
						.proxy(Proxy.NO_PROXY)
						.userAgent(mission.user_agent)
						.header("Cookie", mission.cookie)
						.header("Accept", "*/*")
						.header("Referer",mission.url)
//						.header("Access-Control-Expose-Headers", "Content-Disposition")
//						.header("Range", "bytes=0-")
						.timeout(20000)
						.ignoreContentType(true)
						.ignoreHttpErrors(true)
						.maxBodySize(0)
						.execute();

				if (!handleResponse(response, mission)) {
					return;
				}



				response = Jsoup.connect(mission.url)
						.method(Connection.Method.HEAD)
						.proxy(Proxy.NO_PROXY)
						.userAgent(mission.user_agent)
						.header("Cookie", mission.cookie)
						.header("Accept", "*/*")
						.header("Access-Control-Expose-Headers", "Content-Disposition")
						.header("Referer",mission.url)
						.header("Pragma", "no-cache")
						.header("Range", "bytes=0-")
						.header("Cache-Control", "no-cache")
						.timeout(10000)
						.ignoreContentType(true)
						.ignoreHttpErrors(true)
//						.validateTLSCertificates(false)
						.maxBodySize(0)
						.execute();

//				Log.d("statusCode11111111", "       " + response.statusCode());
//
//				Log.d("response.headers()", "1111" + response.headers());
//
//
//				mission.name = getMissionNameFromResponse(response);
//				Log.d("mission.name", "mission.name111=" + mission.name);
//
//				String contentLength = response.header("Content-Length");
//				mission.length = Long.parseLong(contentLength);
//				Log.d("mission.length", "mission.length=" + mission.length);
//
//				if (!checkLength(mission)) {
//					return;
//				}

				if (!handleResponse(response, mission)) {
					return;
				}

				if (response.statusCode() != 206) {
					// Fallback to single thread if no partial content support
					mission.fallback = true;

					Log.d(TAG, "falling back");
				}

				Log.d("mission.name", "mission.name444=" + mission.name);
				if (TextUtils.isEmpty(mission.name)) {
					mission.name = getMissionNameFromUrl(mission.url);
				}

				Log.d("mission.name", "mission.name555=" + mission.name);

				for (DownloadMission downloadMission : mMissions) {
					if (mission.hasInit && TextUtils.equals(mission.name, downloadMission.name) &&
							(TextUtils.equals(downloadMission.originUrl.trim(), mission.url.trim()) ||
									TextUtils.equals(downloadMission.redictUrl.trim(), mission.url.trim()))) {
						if (downloadMission.finished || downloadMission.running) {
							Log.d("startMission", "finished");
						} else {
							Log.d("startMission", "start");
							downloadMission.start();
						}
						return;
					}
				}

				mission.blocks = mission.length / mission.block_size;

				if (mission.threadCount > mission.blocks) {
					mission.threadCount = (int) mission.blocks;
				}

				if (mission.threadCount <= 0) {
					mission.threadCount = 1;
				}

				if (mission.blocks * mission.block_size < mission.length) {
					mission.blocks++;
				}


				File loacation = new File(mission.location);
				if (!loacation.exists()) {
					loacation.mkdirs();
				}
				File file = new File(mission.location + "/" + mission.name);
				if (!file.exists()) {
					file.createNewFile();
				}

				Log.d(TAG, "storage=" + Utility.getAvailableSize());
				mission.hasInit = true;

				BufferedRandomAccessFile af = new BufferedRandomAccessFile(mission.location + "/" + mission.name, "rw");
				af.setLength(mission.length);
				af.close();

				mission.start();
			} catch (Exception e) {
				// TODO Notify
				throw new RuntimeException(e);
			}
		}
	}

	private boolean handleResponse(Connection.Response response, DownloadMission mission) {
		if (response.statusCode() == 302 || response.statusCode() == 301 || response.statusCode() == 300) {
			String redictUrl = response.header("location");
			Log.d(TAG, "redictUrl=" + redictUrl);
			if (redictUrl != null) {
				mission.url = redictUrl;
				mission.redictUrl = redictUrl;
			}
		} else if (response.statusCode() == 404){
			mission.errCode = DownloadMission.ERROR_SERVER_404;
			mission.notifyError(DownloadMission.ERROR_SERVER_404);
			return false;
		} else if (response.statusCode() == 206){
			Log.d("statusCode11111111", "       " + response.statusCode());
			String contentLength = response.header("Content-Length");
			Log.d("response.headers()", "1111" + response.headers());

			if (TextUtils.isEmpty(mission.name)) {
				mission.name = getMissionNameFromResponse(response);
				Log.d("mission.name", "mission.name333=" + mission.name);
			}

			mission.length = Long.parseLong(contentLength);

			Log.d("mission.length", "mission.length=" + mission.length);

			return checkLength(mission);
		}
		return true;
	}

	private String getMissionNameFromResponse(Connection.Response response) {
		String contentDisposition = response.header("Content-Disposition");
		Log.d("contentDisposition", "contentDisposition=" + contentDisposition);
		if (contentDisposition != null) {
			String[] dispositions = contentDisposition.split(";");
			for (String disposition : dispositions) {
				Log.d("disposition", "disposition=" + disposition);
				if (disposition.contains("filename=")) {
					return disposition.replace("filename=", "");
				}
			}
		}
		return "";
	}

	private String getMissionNameFromUrl(String url) {
		if (!TextUtils.isEmpty(url)) {
			int index = url.lastIndexOf("/");

			if (index > 0) {
				int end = url.lastIndexOf("?");

				if (end < index) {
					end = url.length();
				}

				String name = url.substring(index + 1, end);
				if (name.contains(".")) {
					return name;
				} else {
					return name + ".ext";
				}
			}
		}
		return "未知文件.ext";
	}

	private boolean checkLength(DownloadMission mission) {
		if (mission.length <= 0) {
			mission.errCode = DownloadMission.ERROR_SERVER_UNSUPPORTED;
			mission.notifyError(DownloadMission.ERROR_SERVER_UNSUPPORTED);
			return false;
		} else if (mission.length >= Utility.getAvailableSize()) {
			mission.errCode = DownloadMission.ERROR_NO_ENOUGH_SPACE;
			mission.notifyError(DownloadMission.ERROR_NO_ENOUGH_SPACE);
			return false;
		}
		return true;
	}
}