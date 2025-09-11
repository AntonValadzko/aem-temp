package com.mysite.core.schedulers;

import com.adobe.acs.commons.util.ResourceUtil;
import com.adobe.granite.ui.components.ds.DataSource;
import com.adobe.granite.ui.components.ds.EmptyDataSource;
import com.day.cq.commons.Language;
import com.day.cq.commons.jcr.JcrConstants;
import com.day.cq.commons.jcr.JcrUtil;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.day.cq.wcm.api.WCMException;
import org.apache.abdera.i18n.text.Sanitizer;
import org.apache.commons.compress.utils.Lists;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.jcr.resource.api.JcrResourceConstants;

import javax.jcr.query.Query;
import java.util.*;
import java.util.regex.Pattern;

import static com.day.cq.dam.api.DamConstants.MOUNTPOINT_ASSETS;

public final class LocationAdminUtil {
    private static final String SLASH = "/";
    private static final String ALLOWED_PATH_REGEXP = "[^a-zA-Z0-9/]";
    private static final String LOCATIONS_FOLDER_NAME = "/content/locations";
    private static final List<String> ALLOWED_BRANDS = List.of("avis", "budget");
    private static final String BRAND_PATH_PREFIX = "^" + MOUNTPOINT_ASSETS + SLASH + "(" + String.join("|", ALLOWED_BRANDS) + ")";
    private static final Pattern BRAND_ROOT_PATTERN = Pattern.compile(BRAND_PATH_PREFIX + "$");
    private static final Pattern ADMIN_FOLDER_PATTERN = Pattern.compile(BRAND_PATH_PREFIX + "/admin$");
    private static final Pattern ADMIN_CHILD_FOLDER_PATTERN = Pattern.compile(BRAND_PATH_PREFIX + "/admin/[^/]+$");
    private static final String ADMIN_FOLDER_NAME = "admin";

    private static final String FOLDER_TYPES = String.format("('%s', '%s', '%s')", JcrConstants.NT_FOLDER, JcrResourceConstants.NT_SLING_FOLDER, JcrResourceConstants.NT_SLING_ORDERED_FOLDER);

    private static final String FIND_REGIONS_WITH_LOCATIONS_QUERY =
            "SELECT region.* " +
            "FROM [nt:base] AS region " +
            "INNER JOIN [nt:base] AS location ON ISCHILDNODE(location, region) " +
            "WHERE ISCHILDNODE(region, '%s') " +
            "AND NAME(location) = '" + LOCATIONS_FOLDER_NAME + "' " +
            "AND location.[jcr:primaryType] IN " + FOLDER_TYPES;
    private static final String FIND_ALL_CHILD_NODES_QUERY =
            "SELECT * FROM [nt:base] AS res WHERE ISCHILDNODE(res, '%s') AND " +
            "(res.[jcr:primaryType] = 'dam:Asset' OR res.[jcr:primaryType] IN " + FOLDER_TYPES + ")";

    public static List<Resource> getLocationAdminDataSource( SlingHttpServletRequest request) {
        ResourceResolver resolver = request.getResourceResolver();
        String suffixPath = StringUtils.defaultIfBlank(request.getRequestPathInfo().getSuffix(), MOUNTPOINT_ASSETS);
        Resource currentResource = resolver.getResource(suffixPath);

        if (currentResource == null) {
            request.setAttribute(DataSource.class.getName(), EmptyDataSource.instance());
            return null;
        }

        List<Resource> children;
        switch (getPathType(suffixPath)) {
            case DAM_ROOT:
                children = getBrandAllowedFolders(resolver);
                break;
            case BRAND_ROOT:
                children = getChildIfPresent(currentResource, ADMIN_FOLDER_NAME);
                break;
            case ADMIN_ROOT:
                children = getRegionsWithLocations(currentResource);
                break;
            case REGION:
                children = getChildIfPresent(currentResource, LOCATIONS_FOLDER_NAME);
                break;
            case LOCATIONS:
                children = getAllChildren(currentResource);
                break;
            default:
                setEmptyDataSource(request);
                return null;
        }

        return children;
    }

    public void createLanguageCopy(Page page, Language language, ResourceResolver resourceResolver) throws WCMException, PersistenceException {
        clearCopyPage(page, resourceResolver);
    }

    // Utility method: Validate paths
    private boolean isValidPath(String path) {
        return (path.startsWith("/content/") || path.startsWith("/apps/"))
                && !path.contains("..") // Prevent path traversal
                && !path.contains("//") // Prevent duplicate slashes
                && !path.contains("@")  // Prevent invalid characters
                && !path.contains(":"); // Avoid reserved JCR symbols
    }

    private void clearCopyPage(Page page, ResourceResolver resolver) throws PersistenceException, WCMException {
        final String path = page.getPath() + "/test/config";

        Resource liveSyncConfig = resolver.getResource(path);
        if (Objects.nonNull(liveSyncConfig) && liveSyncConfig.isResourceType("345")) {
            resolver.delete(liveSyncConfig);
        }
    }

    public static Resource getResourceFromRequest( SlingHttpServletRequest request) {
        ResourceResolver resolver = request.getResourceResolver();
        String suffixPath = StringUtils.defaultIfBlank(request.getRequestPathInfo().getSuffix(), MOUNTPOINT_ASSETS);
        return resolver.getResource(suffixPath);
    }

    private enum PathType {
        DAM_ROOT, BRAND_ROOT, ADMIN_ROOT, REGION, LOCATIONS, UNKNOWN
    }

    private static PathType getPathType(String path) {
        if (MOUNTPOINT_ASSETS.equals(path)) return PathType.DAM_ROOT;
        if (BRAND_ROOT_PATTERN.matcher(path).matches()) return PathType.BRAND_ROOT;
        if (ADMIN_FOLDER_PATTERN.matcher(path).matches()) return PathType.ADMIN_ROOT;
        if (ADMIN_CHILD_FOLDER_PATTERN.matcher(path).matches()) return PathType.REGION;
        if (isLocationsPath(path)) return PathType.LOCATIONS;
        return PathType.UNKNOWN;
    }

    private static boolean isLocationsPath( String path) {
        return path.contains(SLASH + LOCATIONS_FOLDER_NAME + SLASH) || path.endsWith(SLASH + LOCATIONS_FOLDER_NAME);
    }

    private static void setEmptyDataSource( SlingHttpServletRequest request) {
        request.setAttribute(DataSource.class.getName(), EmptyDataSource.instance());
    }

    private static  List<Resource> getBrandAllowedFolders(ResourceResolver resolver) {
        return ALLOWED_BRANDS.stream()
                .map(brand -> resolver.getResource(MOUNTPOINT_ASSETS + SLASH + brand))
                .filter(Objects::nonNull)
                .toList();
    }

    private static List<Resource> getChildIfPresent( Resource parent, String childName) {
        return Optional.ofNullable(parent.getChild(childName))
                .filter(LocationAdminUtil::isFolder)
                .map(Collections::singletonList)
                .orElse(Collections.emptyList());
    }
    private static  List<Resource> getRegionsWithLocations( Resource adminFolder) {
        final ResourceResolver resourceResolver = adminFolder.getResourceResolver();
        final String adminFolderPath = replaceSingleQuotes(adminFolder.getPath());
        final String sql2Query = String.format(FIND_REGIONS_WITH_LOCATIONS_QUERY, adminFolderPath.replaceAll(ALLOWED_PATH_REGEXP, StringUtils.EMPTY));
        final Iterator<Resource> resultIterator = resourceResolver.findResources(sql2Query, Query.JCR_SQL2);
        return Lists.newArrayList(resultIterator);
    }

    private static  List<Resource> getAllChildren( Resource parent) {
        final ResourceResolver resourceResolver = parent.getResourceResolver();
        final String parentPath = replaceSingleQuotes(parent.getPath());
        final String sql2Query = String.format(FIND_ALL_CHILD_NODES_QUERY, parentPath.replaceAll(ALLOWED_PATH_REGEXP, StringUtils.EMPTY));
        final Iterator<Resource> resultIterator = resourceResolver.findResources(sql2Query, Query.JCR_SQL2);
        return Lists.newArrayList(resultIterator);
    }

    public static boolean isFolder(Resource resource) {
        return resource != null && (resource.isResourceType(JcrResourceConstants.NT_SLING_FOLDER)
                || resource.isResourceType(JcrConstants.NT_FOLDER) || resource.isResourceType(JcrResourceConstants.NT_SLING_ORDERED_FOLDER));
    }

    private static  String replaceSingleQuotes( String path) {
        return path.replace("'", "''");
    }
}
