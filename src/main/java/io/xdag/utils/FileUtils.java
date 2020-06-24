package io.xdag.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

public class FileUtils {

    public static List<String> recursiveList(String path) throws IOException {

        final List<String> files = new ArrayList<>();

        Files.walkFileTree(Paths.get(path), new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                files.add(file.toString());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }
        });

        return files;
    }

    public static boolean recursiveDelete(String fileName) {
        File file = new File(fileName);
        if (file.exists()) {
            //check if the file is a directory
            if (file.isDirectory()) {
                if ((file.list()).length > 0) {
                    for(String s:file.list()){
                        //call deletion of file individually
                        recursiveDelete(fileName + System.getProperty("file.separator") + s);
                    }
                }
            }

            file.setWritable(true);
            boolean result = file.delete();
            return result;
        } else {
            return false;
        }
    }


    public static byte[] readDnetDat(File file) throws IOException {
//        if (".".equals(root)) {
//            path = root+"/" +path;
//        }
//        File file = new File(path);

        FileInputStream inputStream = new FileInputStream(file);

        byte[] buffer = new byte[2048];

        try {
            while (true) {
                int len = inputStream.read(buffer);
                if (len == -1) {
                    break;
                }

            }
        }finally {
            inputStream.close();
        }

        return buffer;
    }

}
