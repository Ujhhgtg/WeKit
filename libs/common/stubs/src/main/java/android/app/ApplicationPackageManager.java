package android.app;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ChangedPackages;
import android.content.pm.FeatureInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.VersionedPackage;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

@SuppressWarnings({"deprecation", "RedundantThrows"})
public class ApplicationPackageManager extends PackageManager {

    @Override
    public void addPackageToPreferred(@NonNull String packageName) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public boolean addPermission(@NonNull PermissionInfo info) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public boolean addPermissionAsync(@NonNull PermissionInfo info) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public void addPreferredActivity(@NonNull IntentFilter filter, int match, @Nullable ComponentName[] set, @NonNull ComponentName activity) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public boolean canRequestPackageInstalls() {
        throw new RuntimeException("Stub!");
    }

    @Override
    public String[] canonicalToCurrentPackageNames(@NonNull String[] packageNames) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public int checkPermission(@NonNull String permName, @NonNull String packageName) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public int checkSignatures(int uid1, int uid2) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public int checkSignatures(@NonNull String packageName1, @NonNull String packageName2) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public void clearInstantAppCookie() {
        throw new RuntimeException("Stub!");
    }

    @Override
    public void clearPackagePreferredActivities(@NonNull String packageName) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public String[] currentToCanonicalPackageNames(@NonNull String[] packageNames) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public void extendVerificationTimeout(int id, int verificationCodeAtTimeout, long millisecondsToDelay) {
        throw new RuntimeException("Stub!");
    }

    @Nullable
    @Override
    public Drawable getActivityBanner(@NonNull ComponentName activityName) throws NameNotFoundException {
        throw new RuntimeException("Stub!");
    }

    @Nullable
    @Override
    public Drawable getActivityBanner(@NonNull Intent intent) throws NameNotFoundException {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public Drawable getActivityIcon(@NonNull ComponentName activityName) throws NameNotFoundException {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public Drawable getActivityIcon(@NonNull Intent intent) throws NameNotFoundException {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public ActivityInfo getActivityInfo(@NonNull ComponentName component, int flags) throws NameNotFoundException {
        throw new RuntimeException("Stub!");
    }

    @Nullable
    @Override
    public Drawable getActivityLogo(@NonNull ComponentName activityName) throws NameNotFoundException {
        throw new RuntimeException("Stub!");
    }

    @Nullable
    @Override
    public Drawable getActivityLogo(@NonNull Intent intent) throws NameNotFoundException {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public List<PermissionGroupInfo> getAllPermissionGroups(int flags) {
        throw new RuntimeException("Stub!");
    }

    @Nullable
    @Override
    public Drawable getApplicationBanner(@NonNull ApplicationInfo info) {
        throw new RuntimeException("Stub!");
    }

    @Nullable
    @Override
    public Drawable getApplicationBanner(@NonNull String packageName) throws NameNotFoundException {
        throw new RuntimeException("Stub!");
    }

    @Override
    public int getApplicationEnabledSetting(@NonNull String packageName) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public Drawable getApplicationIcon(@NonNull ApplicationInfo info) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public Drawable getApplicationIcon(@NonNull String packageName) throws NameNotFoundException {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public ApplicationInfo getApplicationInfo(@NonNull String packageName, int flags) throws NameNotFoundException {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public CharSequence getApplicationLabel(@NonNull ApplicationInfo info) {
        throw new RuntimeException("Stub!");
    }

    @Nullable
    @Override
    public Drawable getApplicationLogo(@NonNull ApplicationInfo info) {
        throw new RuntimeException("Stub!");
    }

    @Nullable
    @Override
    public Drawable getApplicationLogo(@NonNull String packageName) throws NameNotFoundException {
        throw new RuntimeException("Stub!");
    }

    @Nullable
    @Override
    public ChangedPackages getChangedPackages(int sequenceNumber) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public int getComponentEnabledSetting(@NonNull ComponentName componentName) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public Drawable getDefaultActivityIcon() {
        throw new RuntimeException("Stub!");
    }

    @Nullable
    @Override
    public Drawable getDrawable(@NonNull String packageName, int resid, @Nullable ApplicationInfo appInfo) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public List<ApplicationInfo> getInstalledApplications(int flags) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public List<PackageInfo> getInstalledPackages(int flags) {
        throw new RuntimeException("Stub!");
    }

    @Nullable
    @Override
    public String getInstallerPackageName(@NonNull String packageName) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public byte[] getInstantAppCookie() {
        throw new RuntimeException("Stub!");
    }

    @Override
    public int getInstantAppCookieMaxBytes() {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public InstrumentationInfo getInstrumentationInfo(@NonNull ComponentName className, int flags) throws NameNotFoundException {
        throw new RuntimeException("Stub!");
    }

    @Nullable
    @Override
    public Intent getLaunchIntentForPackage(@NonNull String packageName) {
        throw new RuntimeException("Stub!");
    }

    @Nullable
    @Override
    public Intent getLeanbackLaunchIntentForPackage(@NonNull String packageName) {
        throw new RuntimeException("Stub!");
    }

    @Nullable
    @Override
    public String getNameForUid(int uid) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public int[] getPackageGids(@NonNull String packageName) throws NameNotFoundException {
        throw new RuntimeException("Stub!");
    }

    @Override
    public int[] getPackageGids(@NonNull String packageName, int flags) throws NameNotFoundException {
        throw new RuntimeException("Stub!");
    }

    @Override
    public PackageInfo getPackageInfo(@NonNull VersionedPackage versionedPackage, int flags) throws NameNotFoundException {
        throw new RuntimeException("Stub!");
    }

    @Override
    public PackageInfo getPackageInfo(@NonNull String packageName, int flags) throws NameNotFoundException {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public PackageInstaller getPackageInstaller() {
        throw new RuntimeException("Stub!");
    }

    @Override
    public int getPackageUid(@NonNull String packageName, int flags) throws NameNotFoundException {
        throw new RuntimeException("Stub!");
    }

    @Nullable
    @Override
    public String[] getPackagesForUid(int uid) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public List<PackageInfo> getPackagesHoldingPermissions(@NonNull String[] permissions, int flags) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public PermissionGroupInfo getPermissionGroupInfo(@NonNull String groupName, int flags) throws NameNotFoundException {
        throw new RuntimeException("Stub!");
    }

    @Override
    public PermissionInfo getPermissionInfo(@NonNull String permName, int flags) throws NameNotFoundException {
        throw new RuntimeException("Stub!");
    }

    @Override
    public int getPreferredActivities(@NonNull List<IntentFilter> outFilters, @NonNull List<ComponentName> outActivities, @Nullable String packageName) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public List<PackageInfo> getPreferredPackages(int flags) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public ProviderInfo getProviderInfo(@NonNull ComponentName component, int flags) throws NameNotFoundException {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public ActivityInfo getReceiverInfo(@NonNull ComponentName component, int flags) throws NameNotFoundException {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public Resources getResourcesForActivity(@NonNull ComponentName activityName) throws NameNotFoundException {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public Resources getResourcesForApplication(@NonNull ApplicationInfo app) throws NameNotFoundException {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public Resources getResourcesForApplication(@NonNull String packageName) throws NameNotFoundException {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public ServiceInfo getServiceInfo(@NonNull ComponentName component, int flags) throws NameNotFoundException {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public List<SharedLibraryInfo> getSharedLibraries(int flags) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public FeatureInfo[] getSystemAvailableFeatures() {
        throw new RuntimeException("Stub!");
    }

    @Nullable
    @Override
    public String[] getSystemSharedLibraryNames() {
        throw new RuntimeException("Stub!");
    }

    @Nullable
    @Override
    public CharSequence getText(@NonNull String packageName, int resid, @Nullable ApplicationInfo appInfo) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public Drawable getUserBadgedDrawableForDensity(@NonNull Drawable drawable, @NonNull UserHandle user, @Nullable Rect badgeLocation, int badgeDensity) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public Drawable getUserBadgedIcon(@NonNull Drawable drawable, @NonNull UserHandle user) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public CharSequence getUserBadgedLabel(@NonNull CharSequence label, @NonNull UserHandle user) {
        throw new RuntimeException("Stub!");
    }

    @Nullable
    @Override
    public XmlResourceParser getXml(@NonNull String packageName, int resid, @Nullable ApplicationInfo appInfo) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public boolean hasSystemFeature(@NonNull String featureName) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public boolean hasSystemFeature(@NonNull String featureName, int version) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public boolean isInstantApp() {
        throw new RuntimeException("Stub!");
    }

    @Override
    public boolean isInstantApp(@NonNull String packageName) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public boolean isPermissionRevokedByPolicy(@NonNull String permName, @NonNull String packageName) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public boolean isSafeMode() {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public List<ResolveInfo> queryBroadcastReceivers(@NonNull Intent intent, int flags) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public List<ProviderInfo> queryContentProviders(@Nullable String processName, int uid, int flags) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public List<InstrumentationInfo> queryInstrumentation(@NonNull String targetPackage, int flags) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public List<ResolveInfo> queryIntentActivities(@NonNull Intent intent, int flags) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public List<ResolveInfo> queryIntentActivityOptions(@Nullable ComponentName caller, @Nullable Intent[] specifics, @NonNull Intent intent, int flags) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public List<ResolveInfo> queryIntentContentProviders(@NonNull Intent intent, int flags) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public List<ResolveInfo> queryIntentServices(@NonNull Intent intent, int flags) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public List<PermissionInfo> queryPermissionsByGroup(@Nullable String permissionGroup, int flags) throws NameNotFoundException {
        throw new RuntimeException("Stub!");
    }

    @Override
    public void removePackageFromPreferred(@NonNull String packageName) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public void removePermission(@NonNull String permName) {
        throw new RuntimeException("Stub!");
    }

    @Nullable
    @Override
    public ResolveInfo resolveActivity(@NonNull Intent intent, int flags) {
        throw new RuntimeException("Stub!");
    }

    @Nullable
    @Override
    public ProviderInfo resolveContentProvider(@NonNull String authority, int flags) {
        throw new RuntimeException("Stub!");
    }

    @Nullable
    @Override
    public ResolveInfo resolveService(@NonNull Intent intent, int flags) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public void setApplicationCategoryHint(@NonNull String packageName, int categoryHint) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public void setApplicationEnabledSetting(@NonNull String packageName, int newState, int flags) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public void setComponentEnabledSetting(@NonNull ComponentName componentName, int newState, int flags) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public void setInstallerPackageName(@NonNull String targetPackage, @Nullable String installerPackageName) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public void updateInstantAppCookie(@Nullable byte[] cookie) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public void verifyPendingInstall(int id, int verificationCode) {
        throw new RuntimeException("Stub!");
    }
}
