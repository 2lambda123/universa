package net.sergeych.tools;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * Helper tool for writing files with overwrite protection.
 */
public class FileTool {

    /**
     * Wtires byte[] contents to specified file. If this file already exists - tries to create the same file with suffix _1, _2, etc.
     * @param path full file path to write to
     * @param contents byte[]
     * @return new file name if success, null if fails
     */
    public static String writeFileContentsWithRenaming(String path, byte[] contents) {
        try {
            if (!writeFileContents(path, contents)) {
                List<String> fileParts = getFileParts(path);
                for (int iSuf = 1; iSuf <= 9000; ++iSuf) {
                    String newFilename = fileParts.get(0) + "_" + iSuf + fileParts.get(1);
                    if (writeFileContents(newFilename, contents)) {
                        return newFilename;
                    }
                }
            } else {
                return path;
            }
        } catch (Exception e) {
            return null;
        }
        //writeFileContentsWithRenaming failed: to many files like {path}
        return null;
    }

    /**
     * Gets contents of one file and calls {{@link #writeFileContentsWithRenaming(String, byte[])}} to write it on new filepath.
     * If writes done successfully - remove source file.
     * @param pathFrom full file path to get contents
     * @param pathTo full file path to write to
     * @return new file name if success, null if fails
     */
    public static String moveFileWithRenaming(String pathFrom, String pathTo) {
        try {
            byte[] contents = Files.readAllBytes(Paths.get(pathFrom));
            String newFilename = writeFileContentsWithRenaming(pathTo, contents);
            if (newFilename != null)
                Files.delete(Paths.get(pathFrom));
            return newFilename;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean writeFileContents(String path, byte[] contents) throws Exception {
        File file = new File(path);
        if (file.createNewFile()) {
            FileOutputStream outputStream = new FileOutputStream(file, false);
            outputStream.write(contents);
            outputStream.close();
            return true;
        }
        return false;
    }

    private static List<String> getFileParts(String filename) {
        String ext = "";
        int extPos = filename.lastIndexOf(".");
        if (extPos >= 0)
            ext = filename.substring(extPos);
        String filenameHead = filename;
        if (extPos >= 0)
            filenameHead = filename.substring(0, extPos);
        return Arrays.asList(filenameHead, ext);
    }

}
