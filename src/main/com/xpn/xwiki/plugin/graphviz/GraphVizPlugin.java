/**
 * ===================================================================
 *
 * Copyright (c) 2003,2004 Ludovic Dubost, All rights reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details, published at 
 * http://www.gnu.org/copyleft/lesser.html or in lesser.txt in the
 * root folder of this distribution.

 * Created by
 * User: Ludovic Dubost
 * Date: 23 avr. 2005
 * Time: 00:21:43
 */
package com.xpn.xwiki.plugin.graphviz;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.web.XWikiResponse;
import com.xpn.xwiki.api.Api;
import com.xpn.xwiki.plugin.XWikiDefaultPlugin;
import com.xpn.xwiki.plugin.XWikiPluginInterface;
import com.xpn.xwiki.plugin.alexa.AlexaPluginApi;
import com.xpn.xwiki.plugin.alexa.AlexaPlugin;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.lang.RandomStringUtils;

import java.io.*;

public class GraphVizPlugin extends XWikiDefaultPlugin implements XWikiPluginInterface {
        private static Log mLogger =
                LogFactory.getFactory().getInstance(com.xpn.xwiki.plugin.graphviz.GraphVizPlugin.class);

        private File tempDir;
        private String dotPath;

    public GraphVizPlugin(String name, String className, XWikiContext context) {
        super(name, className, context);
        init(context);
    }

    public String getName() {
        return "graphviz";
    }

    public Api getPluginApi(XWikiPluginInterface plugin, XWikiContext context) {
        return new GraphVizPluginApi((GraphVizPlugin) plugin, context);
    }

    public void flushCache() {
        try {
            File[] filelist = tempDir.listFiles();
              for (int i=0;i<filelist.length;i++) {
                try {
                    filelist[i].delete();
                } catch (Exception e) {}
              }
        } catch (Exception e) {}
    }

    public void init(XWikiContext context) {
        super.init(context);

        File dir = (File) context.getEngineContext().getAttribute("javax.servlet.context.tempdir");
        tempDir = new File(dir, "dot");
        try {
            tempDir.mkdirs();
        } catch (Exception e) {};

        dotPath = context.getWiki().Param("xwiki.plugin.graphviz.dotpath", "dot");
        if (!dotPath.equals("dot")) {
            try {
                File dfile = new File(dotPath);
                if (!dfile.exists())
                    mLogger.error("Cannot find graphiz dot program at " + dotPath);
            } catch (Exception e) {}
        }
    }

    public byte[] getDotImage(String dot) throws IOException {
        return getDotImage(dot, "gif");
    }

    public byte[] getDotImage(String dot, String extension) throws IOException {
        int hashCode = Math.abs(dot.hashCode());
        return getDotImage(hashCode, dot, extension);
    }

    public byte[] getDotImage(int hashCode, String dot, String extension) throws IOException {
         File dfile = getTempFile(hashCode, "dot");
         if (!dfile.exists()) {
             FileWriter fwriter = new FileWriter(dfile);
             fwriter.write(dot);
             fwriter.flush();
             fwriter.close();
         }

        File ofile = getTempFile(hashCode, extension);
        if (!ofile.exists()) {
            Runtime rt = Runtime.getRuntime();
            String command = dotPath + " -T" + extension + " \"" + dfile.getAbsolutePath()
                    +"\" -o\"" + ofile.getAbsolutePath() + "\"";
            Process p = rt.exec(command);
            try {
                int i = 0;
                int max = 10;
                for (i=0;i<max;i++) {
                  Thread.sleep(1000);
                  try {
                       p.exitValue();
                       break;
                  } catch (IllegalThreadStateException e) {
                  }
                }
                // No more than 10 seconds to generate graph
                // Force killing
                if (i>=max)
                    p.destroy();
            } catch (InterruptedException e) {
                mLogger.error("Error while generating image from dot", e);
            }

            if (p.exitValue()!=0) {
                BufferedReader os = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                StringBuffer error = new StringBuffer();
                while (os.ready()) {
                 error.append(os.readLine());
                }
                mLogger.error("Error while generating image from dot: " + error.toString());
            }
        }
        FileInputStream fis = new FileInputStream(ofile);
        byte[] result = new byte[(int)ofile.length()];
        fis.read(result);
        return result;
    }

    public byte[] readDotImage(File ofile) throws FileNotFoundException, IOException {
        FileInputStream fis = new FileInputStream(ofile);
        byte[] result = new byte[(int)ofile.length()];
        fis.read(result);
        return result;

    }

    public String writeDotImage(String dot) throws IOException {
        return writeDotImage(dot, "gif");
    }

    public String writeDotImage(String dot, String extension) throws IOException {
        int hashCode = Math.abs(dot.hashCode());
        getDotImage(hashCode, dot, extension);
        return "" + hashCode + "." + extension;
    }

    public void outputDotImage(String dot, XWikiContext context) throws IOException {
        outputDotImage(dot, "gif", context);
    }

    public void outputDotImage(String dot, String extension, XWikiContext context) throws IOException {
        byte[] dotbytes = getDotImage(dot, extension);
        XWikiResponse response = context.getResponse();
        context.setFinished(true);
        response.setContentLength(dotbytes.length);
        response.setContentType(context.getEngineContext().getMimeType("toto." + extension));
        OutputStream os = response.getOutputStream();
        os.write(dotbytes);
        os.flush();
    }

    public void outputDotImageFromFile(String filename, XWikiContext context) throws IOException {
        File ofile = getTempFile(filename);
        byte[] dotbytes = readDotImage(ofile);
        XWikiResponse response = context.getResponse();
        context.setFinished(true);
        response.setDateHeader("Last-Modified", ofile.lastModified());
        response.setContentLength(dotbytes.length);
        response.setContentType(context.getEngineContext().getMimeType(filename));
        OutputStream os = response.getOutputStream();
        os.write(dotbytes);
    }

    public File getTempFile(String filename) {
        return new File(tempDir, filename);
    }

    public File getTempFile(int hashcode, String extension) {
        return getTempFile("" + hashcode + "." + extension);
    }

    public String getDotImageURL(String dot, XWikiContext context) throws IOException {
        String filename = writeDotImage(dot, "gif");
        return context.getDoc().getAttachmentURL(filename, "dot", context);
    }
}
