/* Scalyr client library
 * Copyright (c) 2011 Scalyr
 * All rights reserved
 */

package com.scalyr.api.params;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.util.Date;

import com.scalyr.api.Converter;
import com.scalyr.api.internal.Logging;
import com.scalyr.api.internal.ScalyrUtil;
import com.scalyr.api.internal.Sleeper;
import com.scalyr.api.json.JSONObject;
import com.scalyr.api.json.JSONParser;

/**
 * A file hosted on the parameter service.
 */
class HostedParameterFile extends ParameterFile {
  private final File cacheFile;
  
  private final ParameterService parameterService;
  
  /**
   * Number of seconds to wait when blocking until an updated version of a parameter file becomes
   * available. After this time, the request will complete and we'll issue a new request. We use a finite
   * value to avoid connection timeouts and the like.
   */
  private static final int MAX_WAIT_TIME = 30;
  
  /**
   * @param cacheDir If not null, then we look for a copy of the parameter file in this directory, and
   *     initialize our state based on that file until we first retrieve it from the server. We also
   *     update the file with a copy of each file fetched from the server.
   */
  protected HostedParameterFile(ParameterService parameterService, String filePath, File cacheDir) {
    super(filePath);
    
    this.parameterService = parameterService;
    
    if (cacheDir != null) {
      cacheFile = new File(cacheDir, filePath.replace('/', '|'));
      fetchInitialStateFromCacheFile();
    } else {
      cacheFile = null;
    }
    
    initiateAsyncFetch(null);
  }
  
  @Override public String toString() {
    return "<hosted parameter file \"" + pathname + "\">";
  }
  
  private void fetchInitialStateFromCacheFile() {
    if (!cacheFile.exists())
      return;
    
    // Retrieve the file's content, and record a new FileState.
    String cacheFileContent;
    try {
      cacheFileContent = ScalyrUtil.readFileContent(cacheFile);
    } catch (UnsupportedEncodingException ex) {
      Logging.warn("Error reading cache file [" + cacheFile.getAbsolutePath() + "]", ex);
      return;
    } catch (IOException ex) {
      Logging.warn("Error reading cache file [" + cacheFile.getAbsolutePath() + "]", ex);
      return;
    }
    
    int headerEnd = cacheFileContent.indexOf('}');
    if (headerEnd <= 0) {
      Logging.warn("Cachefile [" + cacheFile.getAbsolutePath() + "] does not contain a proper header");
      return;
    }
    
    try {
      JSONObject header = (JSONObject) new JSONParser().parse(new StringReader(cacheFileContent.substring(0, headerEnd + 1)));
      
      long version = (long) Converter.toLong(header.get("version"));
      if (version == 0) {
        setFileState(new FileState(version, null, null, null));
      } else {
        long createDate = Converter.toLong(header.get("createDate"));
        long modDate    = Converter.toLong(header.get("modDate"));
        setFileState(new FileState(version, cacheFileContent.substring(headerEnd + 1), new Date(createDate), new Date(modDate)));
      }
    } catch (Exception ex) {
      Logging.warn("Error reading cache file [" + cacheFile.getAbsolutePath() + "]", ex);
    }
  }
  
  @Override protected void noteNewState() {
    super.noteNewState();
    
    if (cacheFile != null) {
      // Record a cached copy of our current state.
      
      JSONObject header = new JSONObject();
      header.put("version", fileState.version);
      if (fileState.content != null) {
        header.put("createDate", fileState.creationDate    .getTime());
        header.put("modDate",    fileState.modificationDate.getTime());
      }
      
      StringBuilder sb = new StringBuilder();
      sb.append(header.toJSONString());
      if (fileState.content != null) {
        sb.append(fileState.content);
      }
      
      ScalyrUtil.writeStringToFile(sb.toString(), cacheFile);
    }
  }
  
  // private static final AtomicInteger idCounter = new AtomicInteger(0);
  
  private void initiateAsyncFetch(final Long expectedVersion_) {
    // final int id = idCounter.incrementAndGet();
    // Logging.log("initiateAsyncFetch " + id + ": path [" + filePath + "], expectedVersion " + expectedVersion);
    
    ParameterService.asyncApiExecutor.execute(new Runnable(){
      Long expectedVersion = expectedVersion_;
      
      @Override public void run() {
        int retryInterval = 500;
        
        while (true) {
          try {
            long startTime = System.currentTimeMillis();
            String rawResponse = parameterService.getFile(getPathname(), expectedVersion, MAX_WAIT_TIME);
          
            JSONObject response = (JSONObject) new JSONParser().parse(rawResponse);
          
            Object statusObj = response.get("status");
            String status = (statusObj != null) ? statusObj.toString() : "error/server/missingStatus";
          
            Object stalenessSlop = response.get("stalenessSlop");
            long stalenessSlopLong = (stalenessSlop != null) ? Converter.toLong(stalenessSlop) : 0;
          
            // Logging.log("initiateAsyncFetch " + id + ": status " + status);
          
            if (status.startsWith("success")) {
              // After a successful response, we quickly issue a new request. We pause slightly
              // simply as a safety measure. Normally, we would not expect a rapid-fire sequence
              // of successful responses -- we should usually wait for MAX_WAIT_TIME. The delay
              // here ensures that even if something goes wrong, we'll still issue at most a
              // couple of requests per second.
              retryInterval = 500;
              
              if (status.startsWith("success/noSuchFile")) {
                updateStalenessBound(stalenessSlopLong + System.currentTimeMillis() - startTime);
                setFileState(new FileState(0, null, null, null));
              } else if (status.startsWith("success/unchanged")) {
                updateStalenessBound(stalenessSlopLong + System.currentTimeMillis() - startTime);
              } else {
                updateStalenessBound(stalenessSlopLong + System.currentTimeMillis() - startTime);
                setFileState(new FileState(Converter.toLong(response.get("version")),
                    (String) response.get("content"),
                    new Date((long)Converter.toLong(response.get("createDate"))),
                    new Date((long)Converter.toLong(response.get("modDate"   )))));
              }
            } else {
              // After any sort of error or backoff response, retry after 5 seconds, successively
              // doubling up to a maximum of 1 minute. 
              if (retryInterval < 5000)
                retryInterval = 5000;
              else
                retryInterval = Math.min(retryInterval*2, 60000);
              
              if (status.startsWith("error/client/limit")) {
                Logging.warn("Parameter server returned status [" + status + "], message [" +
                    response.get("message") + "]; backing off");
                
              } else {
                Logging.warn("Bad response from parameter server (status [" + status + "], message [" +
                    response.get("message") + "])");
              }
            }
          } catch (Exception ex) {
            // Logging.log("initiateAsyncFetch " + id + ": exception");
            Logging.warn("Error communicating with parameter server", ex);
          }
        
          // TODO: throttle requests, to avoid runaway loops in the case of connectivity problems or
          // other systemic problems.
          Sleeper.instance.sleep(retryInterval);
        
          // Logging.log("initiateAsyncFetch " + id + ": recursing");
          synchronized (this) {
            expectedVersion = (fileState != null ? fileState.version : null);
          }
        }
      }});
  }
}
