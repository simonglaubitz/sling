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
package org.apache.sling.servlets.post;

import java.util.Enumeration;

import javax.jcr.Item;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.servlets.HtmlResponse;
import org.apache.sling.api.wrappers.SlingRequestPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds various states and encapsulates methods that are needed to handle a
 * post request.
 */
public abstract class AbstractSlingPostOperation implements SlingPostOperation {

    /**
     * default log
     */
    private final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * Creates a new post processor
     * 
     * @param request the request to operate on
     * @param session jcr session to operate on
     * @param nodeNameGenerator the node name generator. use a servlet scoped
     *            one, so that it can hold states.
     * @param dateParser helper for parsing date strings
     * @param servletContext The ServletContext to use for file upload
     */
    public final void run(SlingHttpServletRequest request, HtmlResponse response) {

        // calculate the paths
        String path = getItemPath(request);
        response.setPath(path);

        // location
        response.setLocation(externalizePath(request, path));

        // parent location
        path = ResourceUtil.getParent(path);
        response.setParentLocation(externalizePath(request, path));

        Session session = request.getResourceResolver().adaptTo(Session.class);

        try {

            doRun(request, response);

            if (session.hasPendingChanges()) {
                session.save();
            }

        } catch (Exception e) {

            log.error("Exception during response processing.", e);
            response.setError(e);

        } finally {
            try {
                if (session.hasPendingChanges()) {
                    session.refresh(false);
                }
            } catch (RepositoryException e) {
                log.warn("RepositoryException in finally block: {}",
                    e.getMessage(), e);
            }
        }

    }

    /**
     * Returns the path of the resource of the request as the item path.
     * <p>
     * This method may be overwritten by extension if the operation has different
     * requirements on path processing.
     */
    protected String getItemPath(SlingHttpServletRequest request) {
        return request.getResource().getPath();
    }
    
    public abstract void doRun(SlingHttpServletRequest request,
            HtmlResponse response) throws RepositoryException;

    /**
     * Returns an external form of the given path prepending the context path
     * and appending a display extension.
     * 
     * @param path the path to externalize
     * @return the url
     */
    protected final String externalizePath(SlingHttpServletRequest request,
            String path) {
        StringBuffer ret = new StringBuffer();
        ret.append(SlingRequestPaths.getContextPath(request));
        ret.append(request.getResourceResolver().map(path));

        // append optional extension
        String ext = request.getParameter(SlingPostConstants.RP_DISPLAY_EXTENSION);
        if (ext != null && ext.length() > 0) {
            if (ext.charAt(0) != '.') {
                ret.append('.');
            }
            ret.append(ext);
        }

        return ret.toString();
    }

    /**
     * Resolves the given path with respect to the current root path.
     * 
     * @param relPath the path to resolve
     * @return the given path if it starts with a '/'; a resolved path
     *         otherwise.
     */
    protected final String resolvePath(String absPath, String relPath) {
        if (relPath.startsWith("/")) {
            return relPath;
        }
        return absPath + "/" + relPath;
    }

    /**
     * Return the "save prefix" to use. the names of request properties must
     * start with that prefix in order to be regarded as input values.
     * 
     * @return the save prefix
     */
    protected final String getSavePrefix(SlingHttpServletRequest request) {
        String savePrefix = request.getParameter(SlingPostConstants.RP_SAVE_PARAM_PREFIX);

        if (savePrefix == null) {
            savePrefix = SlingPostConstants.DEFAULT_SAVE_PARAM_PREFIX;
        }

        if (savePrefix.length() > 0) {
            String prefix = "";
            // if no parameters start with this prefix, it is not used
            Enumeration<?> names = request.getParameterNames();
            while (names.hasMoreElements()) {
                String name = (String) names.nextElement();
                if (name.startsWith(savePrefix)) {
                    prefix = savePrefix;
                    break;
                }
            }
            savePrefix = prefix;
        }

        return savePrefix;
    }

    /**
     * Orders the given node according to the specified command. The following
     * syntax is supported: <xmp> | first | before all child nodes | before A |
     * before child node A | after A | after child node A | last | after all
     * nodes | N | at a specific position, N being an integer </xmp>
     * 
     * @param node node to order
     * @param command specifies the ordering type
     * @throws RepositoryException if an error occurs
     */
    protected void orderNode(SlingHttpServletRequest request, Item item)
            throws RepositoryException {

        String command = request.getParameter(SlingPostConstants.RP_ORDER);
        if (command == null || command.length() == 0) {
            // nothing to do
            return;
        }

        if (!item.isNode()) {
            return;
        }
        
        Node parent = item.getParent();

        String next = null;
        if (command.equals(SlingPostConstants.ORDER_FIRST)) {

            next = parent.getNodes().nextNode().getName();

        } else if (command.equals(SlingPostConstants.ORDER_LAST)) {

            next = "";

        } else if (command.startsWith(SlingPostConstants.ORDER_BEFORE)) {

            next = command.substring(SlingPostConstants.ORDER_BEFORE.length());

        } else if (command.startsWith(SlingPostConstants.ORDER_AFTER)) {

            String name = command.substring(SlingPostConstants.ORDER_AFTER.length());
            NodeIterator iter = parent.getNodes();
            while (iter.hasNext()) {
                Node n = iter.nextNode();
                if (n.getName().equals(name)) {
                    if (iter.hasNext()) {
                        next = iter.nextNode().getName();
                    } else {
                        next = "";
                    }
                }
            }

        } else {
            // check for integer
            try {
                // 01234
                // abcde move a -> 2 (above 3)
                // bcade move a -> 1 (above 1)
                // bacde
                int newPos = Integer.parseInt(command);
                next = "";
                NodeIterator iter = parent.getNodes();
                while (iter.hasNext() && newPos >= 0) {
                    Node n = iter.nextNode();
                    if (n.getName().equals(item.getName())) {
                        // if old node is found before index, need to
                        // inc index
                        newPos++;
                    }
                    if (newPos == 0) {
                        next = n.getName();
                        break;
                    }
                    newPos--;
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    "provided node ordering command is invalid: " + command);
            }
        }

        if (next != null) {
            if (next.equals("")) {
                next = null;
            }
            parent.orderBefore(item.getName(), next);
            if (log.isDebugEnabled()) {
                log.debug("Node {} moved '{}'", item.getPath(), command);
            }
        } else {
            throw new IllegalArgumentException(
                "provided node ordering command is invalid: " + command);
        }
    }

}