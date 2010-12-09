/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Eclipse Public License, Version 1.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.eclipse.org/org/documents/epl-v10.php
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.ide.eclipse.adt.internal.editors.layout.gle2;

import static com.android.ide.eclipse.adt.AndroidConstants.EXT_XML;
import static com.android.ide.eclipse.adt.AndroidConstants.WS_LAYOUTS;
import static com.android.ide.eclipse.adt.AndroidConstants.WS_SEP;
import static org.eclipse.core.resources.IResourceDelta.ADDED;
import static org.eclipse.core.resources.IResourceDelta.CHANGED;
import static org.eclipse.core.resources.IResourceDelta.CONTENT;
import static org.eclipse.core.resources.IResourceDelta.REMOVED;

import com.android.ide.eclipse.adt.AdtPlugin;
import com.android.ide.eclipse.adt.internal.editors.layout.descriptors.LayoutDescriptors;
import com.android.ide.eclipse.adt.internal.project.BaseProjectHelper;
import com.android.ide.eclipse.adt.internal.resources.ResourceType;
import com.android.ide.eclipse.adt.internal.resources.manager.ProjectResourceItem;
import com.android.ide.eclipse.adt.internal.resources.manager.ProjectResources;
import com.android.ide.eclipse.adt.internal.resources.manager.ResourceFile;
import com.android.ide.eclipse.adt.internal.resources.manager.ResourceFolder;
import com.android.ide.eclipse.adt.internal.resources.manager.ResourceManager;
import com.android.ide.eclipse.adt.internal.resources.manager.ResourceManager.IResourceListener;
import com.android.ide.eclipse.adt.io.IFileWrapper;
import com.android.sdklib.SdkConstants;
import com.android.sdklib.annotations.VisibleForTesting;
import com.android.sdklib.io.StreamException;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.swt.widgets.Display;
import org.eclipse.wst.sse.core.StructuredModelManager;
import org.eclipse.wst.sse.core.internal.provisional.IModelManager;
import org.eclipse.wst.sse.core.internal.provisional.IStructuredModel;
import org.eclipse.wst.xml.core.internal.provisional.document.IDOMModel;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * The include finder finds other XML files that are including a given XML file, and does
 * so efficiently (caching results across IDE sessions etc).
 */
public class IncludeFinder {
    /** Qualified name for the per-project persistent property include-map */
    private final static QualifiedName CONFIG_INCLUDES = new QualifiedName(AdtPlugin.PLUGIN_ID,
            "includes");//$NON-NLS-1$

    /**
     * Qualified name for the per-project non-persistent property storing the
     * {@link IncludeFinder} for this project
     */
    private final static QualifiedName INCLUDE_FINDER = new QualifiedName(AdtPlugin.PLUGIN_ID,
            "finder"); //$NON-NLS-1$

    /** Project that the include finder locates includes for */
    private final IProject mProject;

    /** Map from a layout resource name to a set of layouts included by the given resource */
    private Map<String, List<String>> mIncludes = null;

    /**
     * Reverse map of {@link #mIncludes}; points to other layouts that are including a
     * given layouts
     */
    private Map<String, List<String>> mIncludedBy = null;

    /** Flag set during a refresh; ignore updates when this is true */
    private static boolean sRefreshing;

    /** Global (cross-project) resource listener */
    private static ResourceListener sListener;

    /**
     * Constructs an {@link IncludeFinder} for the given project. Don't use this method;
     * use the {@link #get} factory method instead.
     *
     * @param project project to create an {@link IncludeFinder} for
     */
    private IncludeFinder(IProject project) {
        mProject = project;
    }

    /**
     * Returns the {@link IncludeFinder} for the given project
     *
     * @param project the project the finder is associated with
     * @return an {@IncludeFinder} for the given project, never null
     */
    public static IncludeFinder get(IProject project) {
        IncludeFinder finder = null;
        try {
            finder = (IncludeFinder) project.getSessionProperty(INCLUDE_FINDER);
        } catch (CoreException e) {
            // Not a problem; we will just create a new one
        }

        if (finder == null) {
            finder = new IncludeFinder(project);
            try {
                project.setSessionProperty(INCLUDE_FINDER, finder);
            } catch (CoreException e) {
                AdtPlugin.log(e, "Can't store IncludeFinder");
            }
        }

        return finder;
    }

    /**
     * Returns a list of resource names that are included by the given resource
     *
     * @param includer the resource name to return included layouts for
     * @return the layouts included by the given resource
     */
    public List<String> getIncludesFrom(String includer) {
        ensureInitialized();

        return mIncludes.get(includer);
    }

    /**
     * Gets the list of all other layouts that are including the given layout
     *
     * @param included the file that is included
     * @return the files that are including the given file, or null or empty
     */
    public List<String> getIncludedBy(String included) {
        ensureInitialized();
        return mIncludedBy.get(included);
    }

    /** Initialize the inclusion data structures, if not already done */
    private void ensureInitialized() {
        if (mIncludes == null) {
            // Initialize
            if (!readSettings()) {
                // Couldn't read settings: probably the first time this code is running
                // so there is no known data about includes.

                // Yes, these should be multimaps! If we start using Guava replace
                // these with multimaps.
                mIncludes = new HashMap<String, List<String>>();
                mIncludedBy = new HashMap<String, List<String>>();

                scanProject();
                saveSettings();
            }
        }
    }

    // ----- Persistence -----

    /**
     * Create a String serialization of the includes map. The map attempts to be compact;
     * it strips out the @layout/ prefix, and eliminates the values for empty string
     * values. The map can be restored by calling {@link #decodeMap}. The encoded String
     * will have sorted keys.
     *
     * @param map the map to be serialized
     * @return a serialization (never null) of the given map
     */
    @VisibleForTesting
    public static String encodeMap(Map<String, List<String>> map) {
        StringBuilder sb = new StringBuilder();

        if (map != null) {
            // Process the keys in sorted order rather than just
            // iterating over the entry set to ensure stable output
            List<String> keys = new ArrayList<String>(map.keySet());
            Collections.sort(keys);
            for (String key : keys) {
                List<String> values = map.get(key);

                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(key);
                if (values.size() > 0) {
                    sb.append('=').append('>');
                    sb.append('{');
                    boolean first = true;
                    for (String value : values) {
                        if (first) {
                            first = false;
                        } else {
                            sb.append(',');
                        }
                        sb.append(value);
                    }
                    sb.append('}');
                }
            }
        }

        return sb.toString();
    }

    /**
     * Decodes the encoding (produced by {@link #encodeMap}) back into the original map,
     * modulo any key sorting differences.
     *
     * @param encoded an encoding of a map created by {@link #encodeMap}
     * @return a map corresponding to the encoded values, never null
     */
    @VisibleForTesting
    public static Map<String, List<String>> decodeMap(String encoded) {
        HashMap<String, List<String>> map = new HashMap<String, List<String>>();

        if (encoded.length() > 0) {
            int i = 0;
            int end = encoded.length();

            while (i < end) {

                // Find key range
                int keyBegin = i;
                int keyEnd = i;
                while (i < end) {
                    char c = encoded.charAt(i);
                    if (c == ',') {
                        break;
                    } else if (c == '=') {
                        i += 2; // Skip =>
                        break;
                    }
                    i++;
                    keyEnd = i;
                }

                List<String> values = new ArrayList<String>();
                // Find values
                if (i < end && encoded.charAt(i) == '{') {
                    i++;
                    while (i < end) {
                        int valueBegin = i;
                        int valueEnd = i;
                        char c = 0;
                        while (i < end) {
                            c = encoded.charAt(i);
                            if (c == ',' || c == '}') {
                                valueEnd = i;
                                break;
                            }
                            i++;
                        }
                        if (valueEnd > valueBegin) {
                            values.add(encoded.substring(valueBegin, valueEnd));
                        }

                        if (c == '}') {
                            break;
                        }
                        assert c == ',';
                        i++;
                    }
                }

                String key = encoded.substring(keyBegin, keyEnd);
                map.put(key, values);
                i++;
            }
        }

        return map;
    }

    /**
     * Stores the settings in the persistent project storage.
     */
    private void saveSettings() {
        // Serialize the mIncludes map into a compact String. The mIncludedBy map can be
        // inferred from it.
        String encoded = encodeMap(mIncludes);

        try {
            if (encoded.length() >= 2048) {
                // The maximum length of a setting key is 2KB, according to the javadoc
                // for the project class. It's unlikely that we'll
                // hit this -- even with an average layout root name of 20 characters
                // we can still store over a hundred names. But JUST IN CASE we run
                // into this, we'll clear out the key in this name which means that the
                // information will need to be recomputed in the next IDE session.
                mProject.setPersistentProperty(CONFIG_INCLUDES, null);
            } else {
                String existing = mProject.getPersistentProperty(CONFIG_INCLUDES);
                if (!encoded.equals(existing)) {
                    mProject.setPersistentProperty(CONFIG_INCLUDES, encoded);
                }
            }
        } catch (CoreException e) {
            AdtPlugin.log(e, "Can't store include settings");
        }
    }

    /**
     * Reads previously stored settings from the persistent project storage
     *
     * @return true iff settings were restored from the project
     */
    private boolean readSettings() {
        try {
            String encoded = mProject.getPersistentProperty(CONFIG_INCLUDES);
            if (encoded != null) {
                mIncludes = decodeMap(encoded);

                // Set up a reverse map, pointing from included files to the files that
                // included them
                mIncludedBy = new HashMap<String, List<String>>(2 * mIncludes.size());
                for (Map.Entry<String, List<String>> entry : mIncludes.entrySet()) {
                    // File containing the <include>
                    String includer = entry.getKey();
                    // Files being <include>'ed by the above file
                    List<String> included = entry.getValue();
                    setIncludedBy(includer, included);
                }

                return true;
            }
        } catch (CoreException e) {
            AdtPlugin.log(e, "Can't read include settings");
        }

        return false;
    }

    // ----- File scanning -----

    /**
     * Scan the whole project for XML layout resources that are performing includes.
     */
    private void scanProject() {
        ProjectResources resources = ResourceManager.getInstance().getProjectResources(mProject);
        if (resources != null) {
            ProjectResourceItem[] layouts = resources.getResources(ResourceType.LAYOUT);
            for (ProjectResourceItem layout : layouts) {
                List<ResourceFile> sources = layout.getSourceFileList();
                for (ResourceFile source : sources) {
                    updateFileIncludes(source, false);
                }
            }

            return;
        }
    }

    /**
     * Scans the given {@link ResourceFile} and if it is a layout resource, updates the
     * includes in it.
     *
     * @param resourceFile the {@link ResourceFile} to be scanned for includes
     * @param singleUpdate true if this is a single file being updated, false otherwise
     *            (e.g. during initial project scanning)
     * @return true if we updated the includes for the resource file
     */
    @SuppressWarnings("restriction")
    private boolean updateFileIncludes(ResourceFile resourceFile, boolean singleUpdate) {
        String folderName = resourceFile.getFolder().getFolder().getName();
        if (!folderName.equals(SdkConstants.FD_LAYOUT)) {
            // For now we only track layouts in the main layout/ folder;
            // consider merging the various configurations and doing something
            // clever in Show Include.
            return false;
        }

        ResourceType[] resourceTypes = resourceFile.getResourceTypes();
        for (ResourceType type : resourceTypes) {
            if (type == ResourceType.LAYOUT) {
                ensureInitialized();

                String name = resourceFile.getFile().getName();
                int baseEnd = name.length() - EXT_XML.length() - 1; // -1: the dot
                if (baseEnd > 0) {
                    name = name.substring(0, baseEnd);
                }

                List<String> includes = Collections.emptyList();
                if (resourceFile.getFile() instanceof IFileWrapper) {
                    IFile file = ((IFileWrapper) resourceFile.getFile()).getIFile();

                    // See if we have an existing XML model for this file; if so, we can
                    // just look directly at the parse tree
                    boolean hadXmlModel = false;
                    IStructuredModel model = null;
                    try {
                        IModelManager modelManager = StructuredModelManager.getModelManager();
                        model = modelManager.getExistingModelForEdit(file);
                        if (model instanceof IDOMModel) {
                            IDOMModel domModel = (IDOMModel) model;
                            Document document = domModel.getDocument();
                            includes = findIncludesInDocument(document);
                            hadXmlModel = true;
                        }
                    } finally {
                        if (model != null) {
                            model.releaseFromRead();
                        }
                    }

                    // If no XML model we have to read the XML contents and (possibly)
                    // parse it
                    if (!hadXmlModel) {
                        String xml = readFile(file);
                        includes = findIncludes(xml);
                    }
                } else {
                    String xml = readFile(resourceFile);
                    includes = findIncludes(xml);
                }

                if (includes.equals(getIncludesFrom(name))) {
                    // Common case -- so avoid doing settings flush etc
                    return false;
                }

                boolean detectCycles = singleUpdate;
                setIncluded(name, includes, detectCycles);

                if (singleUpdate) {
                    saveSettings();
                }

                return true;
            }
        }

        return false;
    }

    /**
     * Finds the list of includes in the given XML content. It attempts quickly return
     * empty if the file does not include any include tags; it does this by only parsing
     * if it detects the string &lt;include in the file.
     */
    private List<String> findIncludes(String xml) {
        int index = xml.indexOf("<include"); //NON-NLS-1$
        if (index != -1) {
            return findIncludesInXml(xml);
        }

        return Collections.emptyList();
    }

    /**
     * Parses the given XML content and extracts all the included URLs and returns them
     *
     * @param xml layout XML content to be parsed for includes
     * @return a list of included urls, or null
     */
    private List<String> findIncludesInXml(String xml) {
        Document document = null;
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        InputSource is = new InputSource(new StringReader(xml));
        try {
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            document = builder.parse(is);

            return findIncludesInDocument(document);

        } catch (ParserConfigurationException e) {
            // pass -- ignore files we can't parse
        } catch (SAXException e) {
            // pass -- ignore files we can't parse
        } catch (IOException e) {
            // pass -- ignore files we can't parse
        }

        return Collections.emptyList();
    }

    /** Searches the given DOM document and returns the list of includes, if any */
    private List<String> findIncludesInDocument(Document document) {
        NodeList includes = document.getElementsByTagName(LayoutDescriptors.VIEW_INCLUDE);
        if (includes.getLength() > 0) {
            List<String> urls = new ArrayList<String>();
            for (int i = 0; i < includes.getLength(); i++) {
                Element element = (Element) includes.item(i);
                String url = element.getAttribute(LayoutDescriptors.ATTR_LAYOUT);
                if (url.length() > 0) {
                    String resourceName = urlToLocalResource(url);
                    if (resourceName != null) {
                        urls.add(resourceName);
                    }
                }
            }

            return urls;
        }

        return Collections.emptyList();
    }

    /**
     * Returns the layout URL to a local resource name (provided the URL is a local
     * resource, not something in @android etc.) Returns null otherwise.
     */
    private static String urlToLocalResource(String url) {
        if (!url.startsWith("@")) { //$NON-NLS-1$
            return null;
        }
        int typeEnd = url.indexOf('/', 1);
        if (typeEnd == -1) {
            return null;
        }
        int nameBegin = typeEnd + 1;
        int typeBegin = 1;
        int colon = url.lastIndexOf(':', typeEnd);
        if (colon != -1) {
            String packageName = url.substring(typeBegin, colon);
            if ("android".equals(packageName)) { //$NON-NLS-1$
                // Don't want to point to non-local resources
                return null;
            }

            typeBegin = colon + 1;
            assert "layout".equals(url.substring(typeBegin, typeEnd)); //NON-NLS-1$
        }

        return url.substring(nameBegin);
    }

    /**
     * Record the list of included layouts from the given layout
     *
     * @param includer the layout including other layouts
     * @param included the layouts that were included by the including layout
     * @param detectCycles if true, check for cycles and report them as project errors
     */
    @VisibleForTesting
    /* package */ void setIncluded(String includer, List<String> included, boolean detectCycles) {
        // Remove previously linked inverse mappings
        List<String> oldIncludes = mIncludes.get(includer);
        if (oldIncludes != null && oldIncludes.size() > 0) {
            for (String includee : oldIncludes) {
                List<String> includers = mIncludedBy.get(includee);
                if (includers != null) {
                    includers.remove(includer);
                }
            }
        }

        mIncludes.put(includer, included);
        // Reverse mapping: for included items, point back to including file
        setIncludedBy(includer, included);

        if (detectCycles) {
            detectCycles(includer);
        }
    }

    /** Record the list of included layouts from the given layout */
    private void setIncludedBy(String includer, List<String> included) {
        for (String target : included) {
            List<String> list = mIncludedBy.get(target);
            if (list == null) {
                list = new ArrayList<String>(2); // We don't expect many includes
                mIncludedBy.put(target, list);
            }
            if (!list.contains(includer)) {
                list.add(includer);
            }
        }
    }

    /** Start listening on project resources */
    public static void start() {
        assert sListener == null;
        sListener = new ResourceListener();
        ResourceManager.getInstance().addListener(sListener);
    }

    public static void stop() {
        assert sListener != null;
        ResourceManager.getInstance().addListener(sListener);
    }

    /** Listener of resource file saves, used to update layout inclusion data structures */
    private static class ResourceListener implements IResourceListener {
        public void fileChanged(IProject project, ResourceFile file, int eventType) {
            if (sRefreshing) {
                return;
            }

            if ((eventType & (CHANGED | ADDED | REMOVED | CONTENT)) == 0) {
                return;
            }

            IncludeFinder finder = get(project);
            if (finder != null) {
                if (finder.updateFileIncludes(file, true)) {
                    finder.saveSettings();
                }
            }
        }

        public void folderChanged(IProject project, ResourceFolder folder, int eventType) {
            // We only care about layout resource files
        }
    }

    // ----- I/O Utilities -----

    /** Reads the contents of an {@link IFile} and return it as a String */
    private static String readFile(IFile file) {
        InputStream contents = null;
        try {
            contents = file.getContents();
            String charset = file.getCharset();
            return readFile(new InputStreamReader(contents, charset));
        } catch (CoreException e) {
            // pass -- ignore files we can't read
        } catch (UnsupportedEncodingException e) {
            // pass -- ignore files we can't read
        } finally {
            try {
                if (contents != null) {
                    contents.close();
                }
            } catch (IOException e) {
                AdtPlugin.log(e, "Can't read layout file"); //NON-NLS-1$
            }
        }

        return null;
    }

    /** Reads the contents of a {@link ResourceFile} and returns it as a String */
    private static String readFile(ResourceFile file) {
        InputStream contents = null;
        try {
            contents = file.getFile().getContents();
            return readFile(new InputStreamReader(contents));
        } catch (StreamException e) {
            // pass -- ignore files we can't read
        } finally {
            try {
                if (contents != null) {
                    contents.close();
                }
            } catch (IOException e) {
                AdtPlugin.log(e, "Can't read layout file"); //NON-NLS-1$
            }
        }

        return null;
    }

    /** Reads the contents of an {@link InputStreamReader} and return it as a String */
    private static String readFile(InputStreamReader is) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(is);
            StringBuilder sb = new StringBuilder(2000);
            while (true) {
                int c = reader.read();
                if (c == -1) {
                    return sb.toString();
                } else {
                    sb.append((char) c);
                }
            }
        } catch (IOException e) {
            // pass -- ignore files we can't read
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                AdtPlugin.log(e, "Can't read layout file"); //NON-NLS-1$
            }
        }

        return null;
    }

    // ----- Cycle detection -----

    private void detectCycles(String from) {
        // Perform DFS on the include graph and look for a cycle; if we find one, produce
        // a chain of includes on the way back to show to the user
        if (mIncludes.size() > 0) {
            Set<String> seen = new HashSet<String>(mIncludes.size());
            String chain = dfs(from, seen);
            if (chain != null) {
                addError(from, chain);
            } else {
                // Is there an existing error for us to clean up?
                removeErrors(from);
            }
        }
    }

    /** Format to chain include cycles in: a=>b=>c=>d etc */
    private final String CHAIN_FORMAT = "%1$s=>%2$s"; //NON-NLS-1$

    private String dfs(String from, Set<String> seen) {
        seen.add(from);

        List<String> includes = mIncludes.get(from);
        if (includes != null && includes.size() > 0) {
            for (String include : includes) {
                if (seen.contains(include)) {
                    return String.format(CHAIN_FORMAT, from, include);
                }
                String chain = dfs(include, seen);
                if (chain != null) {
                    return String.format(CHAIN_FORMAT, from, chain);
                }
            }
        }

        return null;
    }

    private void removeErrors(String from) {
        final IResource resource = findResource(from);
        if (resource != null) {
            try {
                final String markerId = IMarker.PROBLEM;

                IMarker[] markers = resource.findMarkers(markerId, true, IResource.DEPTH_ZERO);

                for (final IMarker marker : markers) {
                    String tmpMsg = marker.getAttribute(IMarker.MESSAGE, null);
                    if (tmpMsg == null || tmpMsg.startsWith(MESSAGE)) {
                        // Remove
                        runLater(new Runnable() {
                            public void run() {
                                try {
                                    sRefreshing = true;
                                    marker.delete();
                                } catch (CoreException e) {
                                    AdtPlugin.log(e, "Can't delete problem marker");
                                } finally {
                                    sRefreshing = false;
                                }
                            }
                        });
                    }
                }
            } catch (CoreException e) {
                // if we couldn't get the markers, then we just mark the file again
                // (since markerAlreadyExists is initialized to false, we do nothing)
            }
        }
    }

    /** Error message for cycles */
    private static final String MESSAGE = "Found cyclical <include> chain";

    private void addError(String from, String chain) {
        final IResource resource = findResource(from);
        if (resource != null) {
            final String markerId = IMarker.PROBLEM;
            final String message = String.format("%1$s: %2$s", MESSAGE, chain);
            final int lineNumber = 1;
            final int severity = IMarker.SEVERITY_ERROR;

            // check if there's a similar marker already, since aapt is launched twice
            boolean markerAlreadyExists = false;
            try {
                IMarker[] markers = resource.findMarkers(markerId, true, IResource.DEPTH_ZERO);

                for (IMarker marker : markers) {
                    int tmpLine = marker.getAttribute(IMarker.LINE_NUMBER, -1);
                    if (tmpLine != lineNumber) {
                        break;
                    }

                    int tmpSeverity = marker.getAttribute(IMarker.SEVERITY, -1);
                    if (tmpSeverity != severity) {
                        break;
                    }

                    String tmpMsg = marker.getAttribute(IMarker.MESSAGE, null);
                    if (tmpMsg == null || tmpMsg.equals(message) == false) {
                        break;
                    }

                    // if we're here, all the marker attributes are equals, we found it
                    // and exit
                    markerAlreadyExists = true;
                    break;
                }

            } catch (CoreException e) {
                // if we couldn't get the markers, then we just mark the file again
                // (since markerAlreadyExists is initialized to false, we do nothing)
            }

            if (!markerAlreadyExists) {
                runLater(new Runnable() {
                    public void run() {
                        try {
                            sRefreshing = true;

                            // Adding a resource will force a refresh on the file;
                            // ignore these updates
                            BaseProjectHelper.markResource(resource, markerId, message, lineNumber,
                                    severity);
                        } finally {
                            sRefreshing = false;
                        }
                    }
                });
            }
        }
    }

    // FIXME: Find more standard Eclipse way to do this.
    // We need to run marker registration/deletion "later", because when the include
    // scanning is running it's in the middle of resource notification, so the IDE
    // throws an exception
    private static void runLater(Runnable runnable) {
        Display display = Display.findDisplay(Thread.currentThread());
        if (display != null) {
            display.asyncExec(runnable);
        } else {
            AdtPlugin.log(IStatus.WARNING, "Could not find display");
        }
    }

    /**
     * Finds the project resource for the given layout path
     *
     * @param from the resource name
     * @return the {@link IResource}, or null if not found
     */
    private IResource findResource(String from) {
        final IResource resource = mProject.findMember(WS_LAYOUTS + WS_SEP + from + '.' + EXT_XML);
        return resource;
    }

    /**
     * Creates a blank, project-less {@link IncludeFinder} <b>for use by unit tests
     * only</b>
     */
    @VisibleForTesting
    /* package */ static IncludeFinder create() {
        IncludeFinder finder = new IncludeFinder(null);
        finder.mIncludes = new HashMap<String, List<String>>();
        finder.mIncludedBy = new HashMap<String, List<String>>();
        return finder;
    }
}