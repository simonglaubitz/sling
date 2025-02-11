/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.security.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.PropertyUnbounded;
import org.apache.felix.scr.annotations.Service;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.SlingHttpServletResponseWrapper;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(metatype = true,
description = "Request filter adding Content Disposition attachment for certain paths/content types",
label=" Apache Sling Content Disposition Filter")
@Service(value = Filter.class)
@Properties({
        @Property(name = "sling.filter.scope", value = { "request" }, propertyPrivate = true),
        @Property(name = "service.ranking", intValue = -25000, propertyPrivate = true) })
public class ContentDispositionFilter implements Filter {
    
    /** Logger. */
    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    
    @Property(label = "Content Disposition Paths", 
            description = "These paths are filtered by the filter. "+
                    "Each entry is of the form 'path [ \":\" CSV of excluded content types ]'. " +
                    "Invalid entries are logged and ignored."
                    , unbounded = PropertyUnbounded.ARRAY, value = { "" })
    private static final String PROP_CONTENT_DISPOSTION_PATHS = "sling.content.disposition.paths";
    
    private static final boolean DEFAULT_ENABLE_CONTENT_DISPOSTION_ALL_PATHS = false;
    @Property(boolValue = DEFAULT_ENABLE_CONTENT_DISPOSTION_ALL_PATHS ,
              label = "Enable Content Disposition for all paths",
              description ="This flag controls whether to enable" +
                      " Content Disposition for all paths.")
    private static final String PROP_ENABLE_CONTENT_DISPOSTION_ALL_PATHS = "sling.content.disposition.all.paths";
   
    /**
     * Set of paths
     */
    Set<String> contentDispositionPaths;

    /**
     * Array of prefixes of paths
     */
    private String[] contentDispositionPathsPfx;

    private Map<String, Set<String>> contentTypesMapping;
    
    private boolean enableContentDispositionAllPaths;
    
    @Activate
    private void activate(final ComponentContext ctx) {
        final Dictionary props = ctx.getProperties();
        
        String[] contentDispostionProps = PropertiesUtil.toStringArray(props.get(PROP_CONTENT_DISPOSTION_PATHS));
        
        Set<String> paths = new HashSet<String>();
        List<String> pfxs = new ArrayList<String>();
        Map<String, Set<String>> contentTypesMap = new HashMap<String, Set<String>>();
        
        for (String path : contentDispostionProps) {
            path = path.trim();
            if (path.length() > 0) {
                int idx = path.indexOf('*');
                int colonIdx = path.indexOf(":");
                
                if (colonIdx > -1 && colonIdx < idx) {
                    // ':'  in paths is not allowed
                    logger.info("':' in paths is not allowed.");
                } else {
                    String p = null;
                    if (idx >= 0) {
                        if (idx > 0) {
                            p = path.substring(0, idx);
                            pfxs.add(p);
                        } else {
                            // we don't allow "*" - that would defeat the
                            // purpose.
                            logger.info("catch-all wildcard for paths not allowed.");
                        }
                    } else {
                        if (colonIdx > -1) {
                            p = path.substring(0, colonIdx);
                        } else {
                            p = path;
                        }
                        paths.add(p);
                    }                   
                    if (colonIdx != -1 && p != null) {
                        Set <String> contentTypes = getContentTypes(path.substring(colonIdx+1));
                        contentTypesMap.put(p, contentTypes);
                    }
                }

            }
        }
        
        contentDispositionPaths = paths.isEmpty() ? Collections.<String>emptySet() : paths;
        contentDispositionPathsPfx = pfxs.toArray(new String[pfxs.size()]);
        contentTypesMapping = contentTypesMap.isEmpty()?Collections.<String, Set<String>>emptyMap(): contentTypesMap;
        
        enableContentDispositionAllPaths =  PropertiesUtil.toBoolean(props.get(PROP_ENABLE_CONTENT_DISPOSTION_ALL_PATHS),DEFAULT_ENABLE_CONTENT_DISPOSTION_ALL_PATHS);
        
        logger.info("Initialized. content disposition paths: {}, content disposition paths-pfx {}. Enable Content Disposition for all paths is set to {}", new Object[]{
                contentDispositionPaths, contentDispositionPathsPfx, enableContentDispositionAllPaths}
        );
    }
    

    public void init(FilterConfig filterConfig) throws ServletException {
        // nothing to do
    }

    public void destroy() {
        // nothing to do
    }

    public void doFilter(ServletRequest request, ServletResponse response,
            FilterChain chain) throws IOException, ServletException {
  
        final SlingHttpServletRequest slingRequest = (SlingHttpServletRequest) request;
        final SlingHttpServletResponse slingResponse = (SlingHttpServletResponse) response;

        final RewriterResponse rewriterResponse = new RewriterResponse(slingRequest, slingResponse);

        chain.doFilter(request, rewriterResponse);
    }
    
    //---------- PRIVATE METHODS ---------
    
    private static Set<String> getContentTypes(String contentTypes) {
        Set<String> contentTypesSet = new HashSet<String>();
        if (contentTypes != null && contentTypes.length() > 0) {
            String[] contentTypesArray = contentTypes.split(",");
            for (String contentType : contentTypesArray) {
                contentTypesSet.add(contentType);
            }
        }
        return contentTypesSet;
    }
    
    //----------- INNER CLASSES ------------ 

    protected class RewriterResponse extends SlingHttpServletResponseWrapper {
        
        private static final String CONTENT_DISPOSTION = "Content-Disposition";

        private static final String CONTENT_DISPOSTION_ATTACHMENT = "attachment";
        
        private static final String PROP_JCR_DATA = "jcr:data";
        
        private static final String JCR_CONTENT_LEAF = "jcr:content";
        
        static final String ATTRIBUTE_NAME =
                "org.apache.sling.security.impl.ContentDispositionFilter.RewriterResponse.contentType";
        
        /** The current request. */
        private final SlingHttpServletRequest request;

        public RewriterResponse(SlingHttpServletRequest request, SlingHttpServletResponse wrappedResponse) {
            super(wrappedResponse);            
            this.request = request;
        }
        
        /**
         * @see javax.servlet.ServletResponseWrapper#setContentType(java.lang.String)
         */
        public void setContentType(String type) { 
            String previousContentType = (String) request.getAttribute(ATTRIBUTE_NAME);
            
            if (previousContentType != null && previousContentType.equals(type)) {
                return;
            }
            request.setAttribute(ATTRIBUTE_NAME, type);
            Resource resource = request.getResource();
            
            if (enableContentDispositionAllPaths) {
                setContentDisposition(resource);
            } else {
                String resourcePath = resource.getPath();

                boolean contentDispositionAdded = false;
                if (contentDispositionPaths.contains(resourcePath)) {

                    if (contentTypesMapping.containsKey(resourcePath)) {
                        Set <String> exceptions = contentTypesMapping.get(resourcePath);
                        if (!exceptions.contains(type)) {
                            contentDispositionAdded = setContentDisposition(resource);
                        }
                    } else {
                        contentDispositionAdded = setContentDisposition(resource);
                    }
                }            
                if (!contentDispositionAdded) {
                    for (String path : contentDispositionPathsPfx) {
                        if (resourcePath.startsWith(path)) {
                            if (contentTypesMapping.containsKey(path)) {
                                Set <String> exceptions = contentTypesMapping.get(path);
                                if (!exceptions.contains(type)) {
                                    setContentDisposition(resource);
                                    break;
                                }
                            } else {
                                setContentDisposition(resource);
                                break;
                            }

                        }
                    }
                }
            }
            super.setContentType(type);
        }    
        
      //---------- PRIVATE METHODS ---------
        
        private boolean setContentDisposition(Resource resource) {
            boolean contentDispositionAdded = false;
            if (!this.containsHeader(CONTENT_DISPOSTION) && this.isJcrData(resource)) {
                this.addHeader(CONTENT_DISPOSTION, CONTENT_DISPOSTION_ATTACHMENT);
                contentDispositionAdded = true;
            }
            return contentDispositionAdded;
        }
        
        private boolean isJcrData(Resource resource){
            boolean jcrData = false;
            if (resource!= null) {
                ValueMap props = resource.adaptTo(ValueMap.class);
                if (props.containsKey(PROP_JCR_DATA) ) {
                    jcrData = true;
                } else {
                    Resource jcrContent = resource.getChild(JCR_CONTENT_LEAF);
                    if (jcrContent!= null) {
                        props = jcrContent.adaptTo(ValueMap.class);
                        if (props.containsKey(PROP_JCR_DATA) ) {
                            jcrData = true;
                        }
                    }
                }     
            }
            return jcrData;
        }
    }
}
