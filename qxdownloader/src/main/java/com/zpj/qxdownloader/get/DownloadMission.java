package com.zpj.qxdownloader.get;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LongSparseArray;

import com.google.gson.Gson;
import com.hss01248.notifyutil.NotifyUtil;
import com.hss01248.notifyutil.builder.ProgressBuilder;
import com.zpj.qxdownloader.util.Utility;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DownloadMission {
	private static final String TAG = DownloadMission.class.getSimpleName();
	
	public interface MissionListener {
		HashMap<MissionListener, Handler> handlerStore = new HashMap<>();

		void onInit();
		void onStart();
		void onPause();
		void onWaiting();
		void onProgressUpdate(long done, long total);
		void onFinish();
		void onError(int errCode);
	}
	
	public static final int ERROR_SERVER_UNSUPPORTED = 206;
	public static final int ERROR_SERVER_404 = 404;
	public static final int ERROR_UNKNOWN = 233;
	public static final int ERROR_NO_ENOUGH_SPACE = 1000;

	public String uuid = "";
	public String name = "";
	public String url = "";
	public String redictUrl = "";
	public String originUrl = "";
	public String location = "";
	public String cookie = "";
	public String user_agent;
	public long createTime = 0;
	public int notifyId = 0;
	public long blocks = 0;
	public int block_size = DownloadManager.BLOCK_SIZE;
	public long length = 0;
	public long done = 0;
	public int threadCount = 3;
	public int finishCount = 0;
	public ArrayList<Long> threadPositions = new ArrayList<Long>();
//	public final HashMap<Long, Boolean> blockState = new HashMap<Long, Boolean>();
	public final LongSparseArray<Boolean> blockState = new LongSparseArray<>();
	public boolean running = false;
	public boolean finished = false;
	public boolean fallback = false;
	public int errCode = -1;
	public long timestamp = 0;

	public boolean hasInit = false;
	
	public transient boolean recovered = false;
	
	private transient ArrayList<WeakReference<MissionListener>> mListeners = new ArrayList<WeakReference<MissionListener>>();
	private transient boolean mWritingToFile = false;

	private transient final ExecutorService es = Executors.newFixedThreadPool(1);

	private transient final ProgressBuilder progressBuilder = new ProgressBuilder();

	public void initNotification() {
		progressBuilder.setId(getId());
		progressBuilder.setSmallIcon(android.R.mipmap.sym_def_app_icon);
	}
	
	public boolean isBlockPreserved(long block) {
		Boolean state = blockState.get(block);
		return state != null && state;
//		return blockState.containsKey(block) ? blockState.get(block) : false;
	}
	
	public void preserveBlock(long block) {
		synchronized (blockState) {
			blockState.put(block, true);
		}
	}

	public float getProgress() {
		float progress = (float) done / (float) length;
		return progress * 100f;
	}

	private int getId() {
		if (notifyId == 0) {
			notifyId = (int)(createTime / 10000) + (int) (createTime % 10000) * 100000;
		}
		return notifyId;
	}
	
	public void setPosition(int id, long position) {
		threadPositions.set(id, position);
	}
	
	public long getPosition(int id) {
		return threadPositions.get(id);
	}
	
	public synchronized void notifyProgress(long deltaLen) {
		if (!running) return;
		
		if (recovered) {
			recovered = false;
		}
		
		done += deltaLen;
		
		if (done > length) {
			done = length;
		}
		
		if (done != length) {
			Log.d(TAG, "已下载");
			progressBuilder
					.setProgressAndFormat(getProgress(),false, "")
					.setContentTitle("已下载：" + name)
					.setPause(false)
					.setId(getId())
					.show();
			for (WeakReference<MissionListener> ref: mListeners) {
				final MissionListener listener = ref.get();
				if (listener != null) {
					MissionListener.handlerStore.get(listener).post(new Runnable() {
						@Override
						public void run() {
							listener.onProgressUpdate(done, length);
						}
					});
				}
			}
			writeThisToFile();
		}
	}
	
	public synchronized void notifyFinished() {
		if (errCode > 0) return;
		
		finishCount++;
		
		if (finishCount == threadCount) {
			onFinish();
		}
	}
	
	private void onFinish() {
		if (errCode > 0) return;

		Log.d(TAG, "onFinish");
		
		running = false;
		finished = true;
		
//		deleteThisFromFile();
		writeThisToFile();

		NotifyUtil.cancel(getId());
		progressBuilder
				.setContentTitle("已完成：" + name)
				.setId(getId())
				.setPause(true)
				.show();
		for (WeakReference<MissionListener> ref : mListeners) {
			final MissionListener listener = ref.get();
			if (listener != null) {
				MissionListener.handlerStore.get(listener).post(new Runnable() {
					@Override
					public void run() {
						listener.onFinish();
					}
				});
			}
		}
	}
	
	public synchronized void notifyError(int err) {
		errCode = err;

		Log.d("eeeeeeeeeeeeeeeeeeee", "error:" + errCode);
		
		writeThisToFile();

		NotifyUtil.cancel(getId());
		progressBuilder
				.setContentTitle("下载出错" + errCode + ":" + name)
				.setPause(true)
				.setId(getId())
				.show();
		for (WeakReference<MissionListener> ref : mListeners) {
			final MissionListener listener = ref.get();
			MissionListener.handlerStore.get(listener).post(new Runnable() {
				@Override
				public void run() {
					listener.onError(errCode);
				}
			});
		}
	}
	
	public synchronized void addListener(MissionListener listener) {
		Handler handler = new Handler(Looper.getMainLooper());
		MissionListener.handlerStore.put(listener, handler);
		mListeners.add(new WeakReference<>(listener));
	}
	
	public synchronized void removeListener(MissionListener listener) {
		for (Iterator<WeakReference<MissionListener>> iterator = mListeners.iterator();
             iterator.hasNext(); ) {
			WeakReference<MissionListener> weakRef = iterator.next();
			if (listener!=null && listener == weakRef.get())
			{
				iterator.remove();
			}
		}
	}
	
	public void start() {

		if (!running && !finished) {

			running = true;
			ExecutorService executorService;
			if (!fallback) {
				executorService = Executors.newFixedThreadPool(threadCount);
				for (int i = 0; i < threadCount; i++) {
					if (threadPositions.size() <= i && !recovered) {
						threadPositions.add((long) i);
					}
					executorService.submit(new DownloadRunnable(this, i));
//					new Thread(new DownloadRunnable(this, i)).start();
				}
			} else {
				// In fallback mode, resuming is not supported.
				threadCount = 1;
				done = 0;
				blocks = 0;
				executorService = Executors.newFixedThreadPool(1);
				executorService.submit(new DownloadRunnableFallback(this));
//				new Thread(new DownloadRunnableFallback(this)).start();
			}

			writeThisToFile();

			for (WeakReference<MissionListener> ref: mListeners) {
				final MissionListener listener = ref.get();
				if (listener != null) {
					MissionListener.handlerStore.get(listener).post(new Runnable() {
						@Override
						public void run() {
							listener.onStart();
						}
					});
				}
			}
		}
	}
	
	public void pause() {
		if (running) {
			running = false;
			recovered = true;

			writeThisToFile();
			
			// TODO: Notify & Write state to info file
			// if (err)
			Log.d(TAG, "已暂停");
			NotifyUtil.cancel(getId());
			progressBuilder
					.setContentTitle("已暂停：" + name)
					.setPause(true)
					.setId(getId())
					.show();
			for (WeakReference<MissionListener> ref: mListeners) {
				final MissionListener listener = ref.get();
				if (listener != null) {
					MissionListener.handlerStore.get(listener).post(new Runnable() {
						@Override
						public void run() {
							listener.onPause();
						}
					});
				}
			}
		}
	}
	
	public void delete() {
		deleteThisFromFile();
		new File(location + "/" + name).delete();
	}
	
	public void writeThisToFile() {
		if (!mWritingToFile) {
			mWritingToFile = true;
			es.submit(new Runnable() {
				@Override
				public void run() {
					doWriteThisToFile();
					mWritingToFile = false;
				}
			});
//			new Thread() {
//				@Override
//				public void run() {
//					doWriteThisToFile();
//					mWritingToFile = false;
//				}
//			}.start();
		}
	}
	
	private void doWriteThisToFile() {
		synchronized (blockState) {
			Utility.writeToFile(DownloadManagerImpl.TASK_PATH + "/" + uuid + ".zpj", new Gson().toJson(this));
		}
	}
	
	public void deleteThisFromFile() {
		new File(DownloadManagerImpl.TASK_PATH + "/" + uuid + ".zpj").delete();
	}
}
