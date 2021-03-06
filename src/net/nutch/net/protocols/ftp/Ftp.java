/* Copyright (c) 2004 The Nutch Organization.  All rights reserved.   */
/* Use subject to the conditions in http://www.nutch.org/LICENSE.txt. */

package net.nutch.net.protocols.ftp;

import net.nutch.net.protocols.Response;

import javax.activation.MimetypesFileTypeMap;
// 20040427, xing, disabled for now
//import xing.net.nutch.util.magicfile.*;

import org.apache.commons.net.ftp.FTPFileEntryParser;

import net.nutch.net.protocols.HttpDateFormat;

import net.nutch.net.protocols.http.MiscHttpAccounting;
import net.nutch.net.protocols.http.HttpError;

import net.nutch.util.LogFormatter;
import net.nutch.util.NutchConf;

import java.util.logging.Level;
import java.util.logging.Logger;

import java.net.InetAddress;
import java.net.URL;

import java.io.InputStream;
import java.io.Reader;
import java.io.IOException;

/************************************
 * Ftp.java deals with ftp protocol.
 *
 * Configurable parameters are defined under "FTP properties" section
 * in ./conf/nutch-default.xml or similar.
 *
 * @author John Xing
 ***********************************/
public class Ftp {

  public static final Logger LOG =
    LogFormatter.getLogger("net.nutch.net.protocols.ftp.Ftp");

  static final int BUFFER_SIZE = 16384; // 16*1024 = 16384
  private static final int MAX_REDIRECTS = 5;

  int timeout = NutchConf.getInt("ftp.timeout", 10000);

  int maxContentLength = NutchConf.getInt("ftp.content.limit",64*1024);

  String userName = NutchConf.get("ftp.username", "anonymous");
  String passWord = NutchConf.get("ftp.password", "anonymous@example.com");

  // typical/default server timeout is 120*1000 millisec.
  // better be conservative here
  int serverTimeout = NutchConf.getInt("ftp.server.timeout", 60*1000);

  // when to have client start anew
  long renewalTime = -1;

  boolean keepConnection = NutchConf.getBoolean("ftp.keep.connection", false);

  boolean followTalk = NutchConf.getBoolean("ftp.follow.talk", false);

  // ftp client
  Client client = null;
  // ftp dir list entry parser
  FTPFileEntryParser parser = null;

  // 20040412, xing
  // the following three: HttpDateFormat, MimetypesFileTypeMap, MagicFile
  // are placed in each thread before they are checked out to be thread-safe.

  // http date format
  HttpDateFormat httpDateFormat = null;
  // file name extension to mime-type map
  static MimetypesFileTypeMap TYPE_MAP = null;
// 20040427, xing, disabled for now
//  // file magic for determining content type
//  MagicFile magic = null;


  static {
    try {
      // read mime types from config file
      InputStream is =
        NutchConf.getConfResourceAsInputStream
        (NutchConf.get("mime.types.file"));
      if (is == null) {
        LOG.warning
          ("no mime.types.file: won't use url extension for content-type.");
        TYPE_MAP = null;
      } else {
        TYPE_MAP = new MimetypesFileTypeMap(is);
      }
      
      if (is != null)
        is.close();
    } catch (IOException e) {
      LOG.log(Level.SEVERE, "Unexpected error", e);
    }
  }

  // constructor
  public Ftp() {
    this.httpDateFormat = new HttpDateFormat();
  }

  /** Set the timeout. */
  public void setTimeout(int timeout) {
    this.timeout = timeout;
  }

  /** Set the point at which content is truncated. */
  public void setMaxContentLength(int length) {this.maxContentLength = length;}

  /** Set followTalk */
  public void setFollowTalk(boolean followTalk) {
    this.followTalk = followTalk;
  }
  /** Set keepConnection */
  public void setKeepConnection(boolean keepConnection) {
    this.keepConnection = keepConnection;
  }

  /** 
   * Make a single FTP request and return its response in http fashion.
   * If <code>addr</code> is not null, that address will be used.  If
   * <code>httpAccounting</code> is not <code>null</code>, the it's
   * fields will be upated during this request.
   */
  public Response getRawResponse (URL url)
    throws IOException, FtpException {
    return new FtpResponse(this, url, null, null, -1);
    //return new FtpResponse(this, url, null, null, Http.HTTP_VER_NOTSET);
  }

  /**
   * Mimic a single HTTP request and return its response in http fashion,
   * not following * redirects and not translating HTTP errors to exceptions.
   * If <code>addr</code> is not null, that address will be used.  If
   * <code>httpAccounting</code> is not <code>null</code>, it's
   * fields will be upated during this request.
   */
  public Response getRawResponse(URL url, InetAddress addr,
                                  MiscHttpAccounting httpAccounting,
                                  int httpVersion)
    throws IOException, FtpException {
    return new FtpResponse(this, url, addr, httpAccounting, httpVersion);
  }

  /** Returns the content of a URL.
   * Follow redirects and translate HTTP errors to exceptions.
   */
  public Response getResponse(URL url)
    throws IOException, FtpException, HttpError {

    int redirects = 0;
    URL target = url;

    while (true) {
      Response response = new FtpResponse(this, target);   // make a request

      int code = response.getCode();

      if (code == 200) {                          // got a good response
        return response;                          // return it

      } else if (code >= 300 && code < 400) {     // handle redirect
        if (redirects == MAX_REDIRECTS)
          throw new FtpException("Too many redirects: " + url);
        target = new URL(response.getHeader("Location"));
        redirects++;                
        LOG.fine("redirect to " + target); 

      } else {                                    // convert to exception
        //throw new FtpError(code);
        throw new HttpError(code);
      }
    } 
  }

  protected void finalize () {
    try {
      if (this.client != null && this.client.isConnected()) {
        this.client.logout();
        this.client.disconnect();
      }
    } catch (IOException e) {
      // do nothing
    }
  }

  /** For debugging. */
  public static void main(String[] args)
    throws Exception {
    //throws IOException {

    int timeout = -1;
    int maxContentLength = -1;
    String logLevel = "info";
    boolean followTalk = false;
    boolean keepConnection = false;
    boolean dumpContent = false;
    String urlString = null;

    String usage = "Usage: Ftp [-logLevel level] [-followTalk] [-keepConnection] [-timeout N] [-maxContentLength L] [-dumpContent] url";

    if (args.length == 0) {
      System.err.println(usage);
      System.exit(-1);
    }
      
    for (int i = 0; i < args.length; i++) {
      if (args[i].equals("-timeout")) {
        timeout = Integer.parseInt(args[++i]) * 1000;
      } else if (args[i].equals("-logLevel")) {
        logLevel = args[++i];
      } else if (args[i].equals("-followTalk")) {
        followTalk = true;
      } else if (args[i].equals("-keepConnection")) {
        keepConnection = true;
      } else if (args[i].equals("-maxContentLength")) {
        maxContentLength = Integer.parseInt(args[++i]);
      } else if (args[i].equals("-dumpContent")) {
        dumpContent = true;
      } else if (i != args.length-1) {
        System.err.println(usage);
        System.exit(-1);
      } else {
        urlString = args[i];
      }
    }

    Ftp ftp = new Ftp();

    ftp.setFollowTalk(followTalk);
    ftp.setKeepConnection(keepConnection);

    if (timeout != -1)                            // set timeout
      ftp.setTimeout(timeout);

    if (maxContentLength != -1)                   // set maxContentLength
      ftp.setMaxContentLength(maxContentLength);

    // set log level
    LOG.setLevel(Level.parse((new String(logLevel)).toUpperCase()));

    URL url = new URL(urlString);

    Response response = ftp.getRawResponse(url);

    int code = response.getCode();

    System.err.println("Response code: " + code);

    switch (code) {
    case 200:
      System.err.println("Content-Length: "
        + response.getHeader("Content-Length"));
      System.err.println("Content-Type: "
        + response.getHeader("Content-Type"));
      System.err.println("Last-Modified: "
        + response.getHeader("Last-Modified"));
      if (dumpContent) {
        System.out.print(new String(response.getContent()));
      }
      break;
    case 300:
      System.err.println("Redirect to: " + response.getHeader("Location"));
      break;
    case 401:
      System.err.println("Unauthorized (anonymous login failed): " + url);
      break;
    case 404:
      System.err.println("File not found: " + url);
      break;
    default:
      System.err.println("Error during ftp");
    }

    ftp = null;

    // should not need this.
    //System.exit(-1);
  }

}
