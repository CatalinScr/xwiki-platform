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
 * Date: 4 juin 2004
 * Time: 10:43:25
 */
package com.xpn.xwiki.user.impl.xwiki;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.user.api.XWikiGroupService;
import com.xpn.xwiki.user.api.XWikiRightNotFoundException;
import com.xpn.xwiki.user.api.XWikiRightService;
import com.xpn.xwiki.user.api.XWikiUser;
import com.xpn.xwiki.util.Util;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.*;

public class XWikiRightServiceImpl implements XWikiRightService {
    private static final Log log = LogFactory.getLog(XWikiRightServiceImpl.class);
    private static Map actionMap;

    protected void logAllow(String username, String page, String action, String info) {
        if (log.isDebugEnabled())
            log.debug("Access has been granted for (" + username + "," + page + "," + action + "): " + info);
    }

    protected void logDeny(String username, String page, String action, String info) {
        if (log.isInfoEnabled())
            log.info("Access has been denied for (" + username + "," + page + "," + action + "): " + info);
    }

    protected void logDeny(String name, String resourceKey, String accessLevel, String info, Exception e) {
        if (log.isDebugEnabled())
            log.debug("Access has been denied for (" + name + "," + resourceKey + "," + accessLevel + ") at " + info, e);
    }

    public String getRight(String action) {
        if (actionMap == null) {
            actionMap = new HashMap();
            actionMap.put("login", "login");
            actionMap.put("logout", "login");
            actionMap.put("loginerror", "login");
            actionMap.put("view", "view");
            actionMap.put("plain", "view");
            actionMap.put("raw", "view");
            actionMap.put("attach", "view");
            actionMap.put("skin", "view");
            actionMap.put("download", "view");
            actionMap.put("dot", "view");
            actionMap.put("pdf", "view");
            actionMap.put("delete", "delete");
            actionMap.put("commentadd", "comment");
            actionMap.put("register", "register");
        }

        String right = (String)actionMap.get(action);
        if (right==null) {
            return "edit";
        }
        else
            return right;
    }

    public boolean checkAccess(String action, XWikiDocument doc, XWikiContext context) throws XWikiException {
        String username = null;
        XWikiUser user = null;
        boolean needsAuth = false;
        String right = getRight(action);

        if (right.equals("login")) {
            user = context.getWiki().checkAuth(context);
            if (user==null)
                username = "XWiki.XWikiGuest";
            else
                username = user.getUser();

            // Save the user
            context.setUser(username);
            context.getWiki().prepareResources(context);
            logAllow(username, doc.getFullName(), action, "login/logout pages");
            return true;
        }

        if (right.equals("delete")) {
            user = context.getWiki().checkAuth(context);
            String creator = doc.getCreator();
            if ((user!=null)&&(user.getUser()!=null)&&(creator!=null)) {
                if (user.getUser().equals(creator)) {
                    context.getWiki().prepareResources(context);
                    return true;
                }
            }
            right = "admin";
        }

        // We do not need to authenticate twice
        // This seems to cause a problem in virtual wikis
        user = context.getXWikiUser();
        if (user==null) {
        needsAuth = needsAuth(right, context);
        try {
            if (context.getMode()!=XWikiContext.MODE_XMLRPC)
                user = context.getWiki().checkAuth(context);
            else
                user = new XWikiUser(context.getUser());

            if ((user==null)&&(needsAuth)) {
                context.getWiki().prepareResources(context);
                if (context.getRequest()!=null)
                    context.getWiki().getAuthService().showLogin(context);
                logDeny("unauthentified", doc.getFullName(), action, "Authentication needed");
                return false;
            }
        } catch (XWikiException e) {
            if (needsAuth)
                throw e;
        }

        if (user==null)
            username = "XWiki.XWikiGuest";
        else
            username = user.getUser();

         // Save the user
         context.setUser(username);
        } else {
            username = user.getUser();
        }
        context.getWiki().prepareResources(context);

// Check Rights
        try {
// Verify access rights and return if ok
            String docname;
            if (context.getDatabase()!=null) {
                docname = context.getDatabase() + ":" + doc.getFullName();
                if (username.indexOf(":")==-1)
                    username = context.getDatabase() + ":" + username;
            }
            else
                docname = doc.getFullName();

            if (context.getWiki().getRightService().hasAccessLevel(right, username, docname, context)) {
                logAllow(username, docname, action, "access manager granted right");
                return true;
            }
        } catch (Exception e) {
// This should not happen..
            logDeny(username, doc.getFullName(), action, "access manager exception " + e.getMessage());
            e.printStackTrace();
            return false;
        }

        if (user==null) {
// Denied Guest need to be authenticated
            logDeny("unauthentified", doc.getFullName(), action, "Guest has been denied - Redirecting to authentication");
            if (context.getRequest()!=null)
                context.getWiki().getAuthService().showLogin(context);
            return false;
        }
        else {
            logDeny(username, doc.getFullName(), action, "access manager denied right");
            return false;
        }
    }

    private boolean needsAuth(String right, XWikiContext context) {
        boolean needsAuth = false;
        try {
            needsAuth = context.getWiki().getXWikiPreference("authenticate_" + right, "", context).toLowerCase().equals("yes");
        } catch (Exception e) {}
        try {
            needsAuth |=  (context.getWiki().getXWikiPreferenceAsInt("authenticate_" + right, 0, context)==1);
        } catch (Exception e) {}
        try {
            needsAuth |= context.getWiki().getWebPreference("authenticate_" + right, "", context).toLowerCase().equals("yes");
        } catch (Exception e) {}
        try {
            needsAuth |=  (context.getWiki().getWebPreferenceAsInt("authenticate_" + right, 0, context)==1);
        } catch (Exception e) {}
        return needsAuth;
    }

    public boolean hasAccessLevel(String right, String username, String docname, XWikiContext context) throws XWikiException {
        try {
            return hasAccessLevel(right, username, docname, true, context);
        } catch (XWikiException e) {
            return false;
        }
    }

    public boolean checkRight(String name, XWikiDocument doc, String accessLevel,
                              boolean user, boolean allow, boolean global, XWikiContext context) throws XWikiRightNotFoundException {
        String className = global ? "XWiki.XWikiGlobalRights" : "XWiki.XWikiRights";
        String fieldName = user ? "users" : "groups";
        boolean found = false;


        // Get the userdb and the shortname
        String userdatabase = null;
        String shortname = name;
        int i0 = name.indexOf(":");
        if (i0!=-1) {
            userdatabase = name.substring(0,i0);
            shortname = name.substring(i0+1);
        }

        if (log.isDebugEnabled())
            log.debug("Checking right: " + name + "," + doc.getFullName() + "," + accessLevel
                    + "," + user + "," + allow + "," + global);

        Vector vobj = doc.getObjects(className);
        if (vobj!=null)
        {
            for (int i=0;i<vobj.size();i++) {
                BaseObject bobj = (BaseObject) vobj.get(i);
                if (bobj==null)
                    continue;
                String users = bobj.getStringValue(fieldName);
                String levels = bobj.getStringValue("levels");
                boolean allowdeny = (bobj.getIntValue("allow")==1);

                if (allowdeny == allow) {
                    String[] levelsarray = StringUtils.split(levels," ,|");
                    if (ArrayUtils.contains(levelsarray, accessLevel)) {
                        if (log.isDebugEnabled())
                            log.debug("Found a right for " + allow);
                        found = true;
                        String[] userarray = StringUtils.split(users," ,|");

                        // In the case where the document database and the user database is the same
                        // then we allow the usage of the short name, otherwise the fully qualified name is requested
                        if (context.getDatabase().equals(userdatabase)) {
                            if (ArrayUtils.contains(userarray, shortname)) {
                                if (log.isDebugEnabled())
                                    log.debug("Found matching right in " + users + " for " + shortname);
                            return true;
                            }
                            // We should also allow to skip "XWiki." from the usernames and group lists
                            String veryshortname = shortname.substring(shortname.indexOf(".")+1);
                            if (ArrayUtils.contains(userarray, veryshortname)) {
                                if (log.isDebugEnabled())
                                    log.debug("Found matching right in " + users + " for " + shortname);
                            return true;
                            }
                        }

                        if ((context.getDatabase()!=null)&&
                                (ArrayUtils.contains(userarray, name))) {
                            if (log.isDebugEnabled())
                                log.debug("Found matching right in " + users + " for " + name);
                            return true;
                        }
                    }
                }
            }
        }

        if (log.isDebugEnabled())
            log.debug("Searching for matching rights at group level");

// Didn't found right at this level.. Let's go to group level
        Map grouplistcache = (Map)context.get("grouplist");
        if (grouplistcache==null) {
            grouplistcache = new HashMap();
            context.put("grouplist", grouplistcache);
        }

        Collection grouplist = new ArrayList();
        XWikiGroupService groupService = context.getWiki().getGroupService();
        String key = context.getDatabase() + ":" + name;
        Collection grouplist1 = (Collection) grouplistcache.get(key);

        if (grouplist1==null) {
            grouplist1 = new ArrayList();
            try {
                    Collection glist = groupService.listGroupsForUser(name, context);
                    Iterator it = glist.iterator();
                    while (it.hasNext()) {
                         grouplist1.add(context.getDatabase() + ":" + it.next());
                    }
            } catch (Exception e) {}

            if (grouplist1!=null)
                grouplistcache.put(key, grouplist1);
            else
                grouplistcache.put(key, new ArrayList());
        }

        if (grouplist1!=null)
            grouplist.addAll(grouplist1);

        if (context.isVirtual()) {
            String database = context.getDatabase();
            try {
                shortname = Util.getName(name, context);

                if (!database.equals(context.getDatabase())) {
                    String key2 = context.getDatabase() + ":" + name;
                    Collection grouplist2 = (Collection) grouplistcache.get(key2);

                    if (grouplist2==null) {
                    Collection glist = groupService.listGroupsForUser(shortname, context);
                    Iterator it = glist.iterator();
                    while (it.hasNext()) {
                        grouplist2.add(context.getDatabase() + ":" + it.next());
                    }
                    if (grouplist2!=null)
                        grouplistcache.put(key2, grouplist2);
                    else
                        grouplistcache.put(key2, new ArrayList());
                    }

                    if (grouplist2!=null)
                        grouplist.addAll(grouplist2);
                }
            } catch (Exception e) {
            } finally {
                context.setDatabase(database);
            }
        }

        if (log.isDebugEnabled())
            log.debug("Searching for matching rights for " + ((grouplist==null) ? "0" : "" + grouplist.size())
                    + " groups: " + grouplist);

        if (grouplist!=null) {
            Iterator groupit = grouplist.iterator();
            while (groupit.hasNext()) {
                String group = (String) groupit.next();
                try {
                    // We need to construct the full group name to make sure the groups are
                    // handled separately
                    boolean result = checkRight(group,doc, accessLevel, false, allow, global, context);
                    if (result)
                        return true;
                } catch (XWikiRightNotFoundException e) {
                }
                catch (Exception e) {
                    // This should not happen
                    e.printStackTrace();
                }
            }
        }

        if (log.isDebugEnabled())
            log.debug("Finished searching for rights for " + name + ": " + found);

        if (found)
            return false;
        else
            throw new XWikiRightNotFoundException();
    }

    public boolean hasAccessLevel(String accessLevel, String name, String resourceKey,
                                  boolean user, XWikiContext context) throws XWikiException {
        boolean deny = false;
        boolean allow = false;
        boolean allow_found = false;
        boolean deny_found = false;
        String database = context.getDatabase();
        XWikiDocument xwikimasterdoc;

        if (name.equals("XWiki.XWikiGuest")||name.endsWith(":XWiki.XWikiGuest")) {
            if (needsAuth(accessLevel, context))
                return false;
        }

        if (name.equals("XWiki.superadmin")||name.endsWith(":XWiki.superadmin")) {
             logAllow(name, resourceKey, accessLevel, "super admin level");
             return true;
        }

        try {
            // The master user and programming rights are checked in the main wiki
            context.setDatabase(context.getWiki().getDatabase());
            xwikimasterdoc = context.getWiki().getDocument("XWiki.XWikiPreferences", context);
// Verify XWiki Master super user
            try {
                allow = checkRight(name, xwikimasterdoc , "admin", true, true, true, context);
                if (allow) {
                    logAllow(name, resourceKey, accessLevel, "master admin level");
                    return true;
                }
            } catch (XWikiRightNotFoundException e) {}

// Verify XWiki programming right
            if (accessLevel.equals("programming")) {
                // Programming right can only been given if user is from main wiki
                if (!name.startsWith(context.getWiki().getDatabase() + ":"))
                    return false;

                try {
                    allow = checkRight(name, xwikimasterdoc , "programming", true, true, true, context);
                    if (allow) {
                        logAllow(name, resourceKey, accessLevel, "programming level");
                        return true;
                    }
                    else {
                        logDeny(name, resourceKey, accessLevel, "programming level");
                        return false;
                    }
                } catch (XWikiRightNotFoundException e) {}
                logDeny(name, resourceKey, accessLevel, "programming level (no right found)");
                return false;
            }
        } finally {
            // The next rights are checked in the virtual wiki
            context.setDatabase(database);
        }

// Verify XWiki register right
        if (accessLevel.equals("register")) {
            try {
                allow = checkRight(name, xwikimasterdoc , "register", true, true, true, context);
                if (allow) {
                    logAllow(name, resourceKey, accessLevel, "register level");
                    return true;
                }
                else {
                    logDeny(name, resourceKey, accessLevel, "register level");
                    return false;
                }
            } catch (XWikiRightNotFoundException e) {}
            logDeny(name, resourceKey, accessLevel, "register level (no right found)");
            return false;
        }

        try {
            // Verify Wiki Owner
            String wikiOwner = context.getWikiOwner();
            if (wikiOwner!=null) {
                if (wikiOwner.equals(name)) {
                    logAllow(name, resourceKey, accessLevel, "admin level from wiki ownership");
                    return true;
                }
            }

            XWikiDocument xwikidoc = null;
            if (context.getDatabase().equals(context.getWiki().getDatabase()))
                xwikidoc = xwikimasterdoc;
            else
                xwikidoc = context.getWiki().getDocument("XWiki.XWikiPreferences", context);

            // Verify XWiki super user
            try {
                allow = checkRight(name, xwikidoc , "admin", true, true, true, context);
                if (allow) {
                    logAllow(name, resourceKey, accessLevel, "admin level");
                    return true;
                }
            } catch (XWikiRightNotFoundException e) {}

// Verify Web super user
            String web = Util.getWeb(resourceKey);
            XWikiDocument webdoc = context.getWiki().getDocument(web, "WebPreferences", context);
            try {
                allow = checkRight(name, webdoc , "admin", true, true, true, context);
                if (allow) {
                    logAllow(name, resourceKey, accessLevel, "web admin level");
                    return true;
                }
            } catch (XWikiRightNotFoundException e) {}

            // First check if this document is denied to the specific user
            resourceKey = Util.getName(resourceKey, context);
            XWikiDocument doc = context.getWiki().getDocument(resourceKey, context);
            try {
                deny = checkRight(name, doc, accessLevel, true, false, false, context);
                deny_found = true;
                if (deny) {
                    logDeny(name, resourceKey, accessLevel, "document level");
                    return false;
                }
            } catch (XWikiRightNotFoundException e) {}

            try {
                allow = checkRight(name, doc , accessLevel, true, true, false, context);
                allow_found = true;
                if (allow) {
                    logAllow(name, resourceKey, accessLevel, "document level");
                    return true;
                }
            } catch (XWikiRightNotFoundException e) {}


// Check if this document is denied/allowed
// through the web WebPreferences Global Rights
            try {
                deny =  checkRight(name, webdoc, accessLevel, true, false, true, context);
                deny_found = true;
                if (deny) {
                    logDeny(name, resourceKey, accessLevel, "web level");
                    return false;
                }
            } catch (XWikiRightNotFoundException e) {}

            // If a right was found at the document level
            // then we cannot check the web rights anymore
            if (!allow_found) {
            try {
                allow = checkRight(name, webdoc , accessLevel, true, true, true, context);
                allow_found = true;
                if (allow) {
                    logAllow(name, resourceKey, accessLevel, "web level");
                    return true;
                }
            } catch (XWikiRightNotFoundException e) {}
            }
// Check if this document is denied/allowed
// through the XWiki.XWikiPreferences Global Rights
            try {
                deny = checkRight(name, xwikidoc , accessLevel, true, false, true, context);
                deny_found = true;
                if (deny) {
                    logDeny(name, resourceKey, accessLevel, "xwiki level");
                    return false;
                }
            } catch (XWikiRightNotFoundException e) {}

            // If a right was found at the document or web level 
            // then we cannot check the web rights anymore
            if (!allow_found) {
            try {
                allow = checkRight(name, xwikidoc , accessLevel, true, true, true, context);
                allow_found = true;
                if (allow) {
                    logAllow(name, resourceKey, accessLevel, "xwiki level");
                    return true;
                }
            } catch (XWikiRightNotFoundException e) {}
            }

// If neither doc, web or topic had any allowed ACL
// and that all users that were not denied
// should be allowed.
            if (!allow_found) {
                    logAllow(name, resourceKey, accessLevel, "global level (no restricting right)");
                    return true;
            }
            else {
                logDeny(name, resourceKey, accessLevel, "global level (restricting right was found)");
                return false;
            }

        } catch (XWikiException e) {
            logDeny(name, resourceKey, accessLevel, "global level (exception)", e);
            e.printStackTrace();
            return false;
        }
        finally {
            context.setDatabase(database);
        }
    }

    public boolean hasProgrammingRights(XWikiContext context) {
        return hasProgrammingRights(context.getDoc(), context);
    }

    public boolean hasProgrammingRights(XWikiDocument doc, XWikiContext context) {
        try {
            if (doc==null)
                return false;

            String username = doc.getAuthor();

            if (username==null)
                return false;

            String docname;
            if (context.getDatabase()!=null) {
                docname = context.getDatabase() + ":" + doc.getFullName();
                if (username.indexOf(":")==-1)
                    username = context.getDatabase() + ":" + username;
            }
            else
                docname = doc.getFullName();

            // programming rights can only been given for user of the main wiki
            if (context.getWiki().isVirtual()) {
             String maindb = context.getWiki().getDatabase();
             if ((maindb==null)||(!username.startsWith(maindb)))
                return false;
            }

            return hasAccessLevel("programming", username, docname, context);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean hasAdminRights(XWikiContext context) {
        boolean hasAdmin = false;
        try {
            hasAdmin = hasAccessLevel("admin", context.getUser(),
                    "XWiki.XWikiPreferences", context);
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (!hasAdmin) {
            try {
                hasAdmin = hasAccessLevel("admin", context.getUser(),
                        context.getDoc().getWeb() + ".WebPreferences", context);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return hasAdmin;
    }

}
