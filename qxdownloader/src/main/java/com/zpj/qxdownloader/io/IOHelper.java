//package com.zpj.qxdownloader.io;
//
//import java.util.concurrent.ExecutorService;
//import java.util.concurrent.Executors;
//
//public class IOHelper {
//
//    private static ExecutorService executorService = Executors.newFixedThreadPool(3);
//
//    public static void write(Runnable runnable) {
//        executorService.submit(runnable);
//    }
//
//}