#!/usr/bin/env python3
"""Build the bounded runtime projection of TELEMETRY_EXTENSION_EVENT_MAPPING.md.

Usage: python3 scripts/generate-event-name-catalog.py --mapping /path/to/mapping.md
Add --check to verify the checked-in asset instead of rewriting it.

Only literal name/type/source triples are extracted; eventData and identifiers
never enter the asset. API associations below are reviewed policy, not inferred
from requestContent, nested eventData, API table proximity, or a method-name regex.
Unlisted associations are deliberately SDK-internal/wild observations.
"""

import argparse
import collections
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / "code/telemetry/src/main/assets/aep-telemetry/event-name-catalog.json"
PROVENANCE = ROOT / "scripts/event-name-catalog-provenance.json"
CORE = "com.adobe.module.core"
HUB = "com.adobe.module.eventhub"
LEGACY_ID = "com.adobe.module.identity"
MSG = "com.adobe.messaging"
EDGE = "com.adobe.edge"
EDGE_ID = "com.adobe.edge.identity"
CONSENT = "com.adobe.edge.consent"
ANALYTICS = "com.adobe.module.analytics"
AUDIENCE = "com.adobe.module.audience"
CAMPAIGN = "com.adobe.module.campaign"
CC = "com.adobe.module.campaignclassic"
TARGET = "com.adobe.module.target"
MEDIA = "com.adobe.module.media"
EDGE_MEDIA = "com.adobe.edge.media"
OPTIMIZE = "com.adobe.optimize"
PLACES = "com.adobe.places"
FLAGS = "com.adobe.flags"
OFFER = "com.adobe.module.offerdecisioning"
PROFILE = "com.adobe.module.userProfile"


def api(extension, *names):
    return extension, sorted(set(names))


TRACK_METHODS = (
    "trackSessionStart", "trackPlay", "trackPause", "trackComplete", "trackSessionEnd",
    "trackError", "trackEvent", "updateQoEObject", "updateCurrentPlayhead",
)

# Names shared by public delegators retain candidates: metadata cannot recover
# the app's original entry point. AEP responses/notifications and SDK child
# requests intentionally have no public API association in this policy.
ASSOCIATIONS = {
    "Set Advertising Identifier": api(CORE, "setAdvertisingIdentifier"),
    "Set Push Identifier": api(CORE, "setPushIdentifier"),
    "Update Profile Attributes": api(CORE, "updateProfileAttributes"),
    "Collect PII": api(CORE, "collectPii"),
    "Collect Data": api(HUB, "collectMessageInfo", "onReceive"),
    "Configure with App ID": api(CORE, "configureWithAppID", "initialize"),
    "Configure with File Path": api(CORE, "configureWithFileInAssets", "configureWithFileInPath", "initialize"),
    "Configuration Update": api(CORE, "setPrivacyStatus", "updateConfiguration"),
    "Clear Updated Configuration": api(CORE, "clearUpdatedConfiguration"),
    "Privacy Status Request": api(CORE, "getPrivacyStatus"),
    "Get SDK Identities": api(CORE, "getSdkIdentities"),
    "Configuration Response Identity": api(CORE, "getSdkIdentities"),
    "Reset Identities Request": api(CORE, "resetIdentities"),
    "Lifecycle Resume": api(CORE, "lifecycleStart"),
    "Lifecycle Pause": api(CORE, "lifecyclePause"),
    "Analytics Track": api(CORE, "trackAction", "trackState"),
    "IdentityRequestIdentity": api(LEGACY_ID, "getIdentifiers", "getExperienceCloudId",
                                 "getUrlVariables", "appendVisitorInfoForURL",
                                 "syncIdentifiers", "syncIdentifier"),
    "IDENTITY_RESPONSE_CONTENT_ONE_TIME": api(LEGACY_ID, "getIdentifiers", "getExperienceCloudId"),
    "IDENTITY_URL_VARIABLES": api(LEGACY_ID, "getUrlVariables"),
    "IDENTITY_APPENDED_URL": api(LEGACY_ID, "appendVisitorInfoForURL"),
    "Refresh in-app messages": api(MSG, "refreshInAppMessages"),
    "Update propositions": api(MSG, "updatePropositionsForSurfaces", "handleNotificationResponse"),
    "Get propositions": api(MSG, "getPropositionsForSurfaces"),
    "Message propositions response": api(MSG, "getPropositionsForSurfaces"),
    "Finalize propositions response": api(MSG, "refreshInAppMessages",
                                         "updatePropositionsForSurfaces", "handleNotificationResponse"),
    "Clear cached propositions": api(MSG, "clearCachedPropositions"),
    "Push notification received": api(MSG, "trackPushReceived"),
    "Push notification interaction event": api(MSG, "handleNotificationResponse"),
    "Push tracking status event": api(MSG, "handleNotificationResponse"),
    "Track propositions": api(MSG, "track"),
    "Assurance Start Session": api("com.adobe.assurance", "startSession"),
    "Assurance Start Session (Quick Connect)": api("com.adobe.assurance", "startSession"),
    "Live Update Event Tracking": api(HUB, "trackLiveUpdateEvent", "triggerLocalLiveUpdate",
                                     "handleNotificationResponse", "trackTopicSubscribed",
                                     "trackTopicUnsubscribed"),
    "Concierge Data Handoff Event": api("brandconcierge", "sendDataHandoff"),
    "Concierge Data Handoff Event Response": api("brandconcierge", "sendDataHandoff"),
    # Legacy Offer also uses Edge.sendEvent: this signature cannot identify the
    # original public entry or one API owner from immutable Hub metadata.
    "AEP Request Event": api(HUB, "sendEvent", "prefetchOffers"),
    "Edge Request Location Hint": api(EDGE, "getLocationHint"),
    "Edge Location Hint Response": api(EDGE, "getLocationHint"),
    "Edge Update Location Hint": api(EDGE, "setLocationHint"),
    "Edge Identity Request ECID": api(EDGE_ID, "getExperienceCloudId"),
    "Edge Identity Request Identities": api(EDGE_ID, "getIdentities"),
    "Edge Identity Response Content One Time": api(EDGE_ID, "getExperienceCloudId", "getIdentities"),
    "Edge Identity Request URL Variables": api(EDGE_ID, "getUrlVariables"),
    "Edge Identity Response URL Variables": api(EDGE_ID, "getUrlVariables"),
    "Edge Identity Update Identities": api(EDGE_ID, "updateIdentities"),
    "Edge Identity Remove Identities": api(EDGE_ID, "removeIdentity"),
    "Update Operational Data": api(EDGE_ID, "updateOperationalData"),
    "Consent Update Request": api(CONSENT, "update"),
    "Get Consents Request": api(CONSENT, "getConsents"),
    "Get Consents Response": api(CONSENT, "getConsents"),
    "GetTrackingIdentifier": api(ANALYTICS, "getTrackingIdentifier"),
    "GetVisitorIdentifier": api(ANALYTICS, "getVisitorIdentifier"),
    "UpdateVisitorIdentifier": api(ANALYTICS, "setVisitorIdentifier"),
    "GetQueueSize": api(ANALYTICS, "getQueueSize"),
    "ClearHitsQueue": api(ANALYTICS, "clearQueue"),
    "ForceKickHits": api(ANALYTICS, "sendQueuedHits"),
    "TrackingIdentifierValue": api(ANALYTICS, "getTrackingIdentifier", "getVisitorIdentifier", "setVisitorIdentifier"),
    "QueueSizeValue": api(ANALYTICS, "getQueueSize"),
    "AudienceRequestIdentity": api(AUDIENCE, "getVisitorProfile"),
    "AudienceRequestContent": api(AUDIENCE, "signalWithData"),
    "AudienceRequestReset": api(AUDIENCE, "reset"),
    "Audience Manager Identities": api(AUDIENCE, "getVisitorProfile"),
    "Audience Manager Profile": api(AUDIENCE, "signalWithData"),
    "setLinkageFields Event": api(CAMPAIGN, "setLinkageFields"),
    "resetLinkageFields Event": api(CAMPAIGN, "resetLinkageFields"),
    "CampaignClassic Register Device": api(CC, "registerDevice"),
    "CampaignClassic Track Notification Receive": api(CC, "trackNotificationReceive", "handleRemoteMessage"),
    "CampaignClassic Track Notification Click": api(CC, "trackNotificationClick"),
    "Device Registration Status": api(CC, "registerDevice"),
    "TargetPrefetchRequest": api(TARGET, "prefetchContent"),
    "TargetLoadRequest": api(TARGET, "retrieveLocationContent"),
    "TargetLocationsDisplayed": api(TARGET, "displayedLocations"),
    "TargetLocationClicked": api(TARGET, "clickedLocation"),
    "TargetGetThirdPartyIdentifier": api(TARGET, "getThirdPartyId"),
    "TargetSetThirdPartyIdentifier": api(TARGET, "setThirdPartyId"),
    "TargetGetTnTIdentifier": api(TARGET, "getTntId"),
    "TargetSetTnTIdentifier": api(TARGET, "setTntId"),
    "TargetGetSessionIdentifier": api(TARGET, "getSessionId"),
    "TargetSetSessionIdentifier": api(TARGET, "setSessionId"),
    "TargetRequestReset": api(TARGET, "resetExperience"),
    "TargetClearPrefetchCache": api(TARGET, "clearPrefetchCache"),
    "TargetSetPreviewRestartDeeplink": api(TARGET, "setPreviewRestartDeepLink"),
    "TargetRawRequest": api(TARGET, "executeRawRequest"),
    "TargetRawNotifications": api(TARGET, "sendRawNotifications"),
    "TargetResponseIdentity": api(TARGET, "getThirdPartyId", "getTntId", "getSessionId"),
    "TargetPrefetchResponse": api(TARGET, "prefetchContent"),
    "TargetRequestResponse": api(TARGET, "retrieveLocationContent"),
    "TargetRawResponse": api(TARGET, "executeRawRequest"),
    "Media::CreateTrackerRequest": api(MEDIA, "createTracker"),
    "Media::TrackMedia": api(MEDIA, *TRACK_METHODS),
    "Media::SessionCreated": api(MEDIA, "trackSessionStart"),
    "Edge Media CreateTrackerRequest": api(EDGE_MEDIA, "createTracker"),
    "Edge Media TrackMedia": api(EDGE_MEDIA, *TRACK_METHODS),
    "Optimize Update Propositions Request": api(OPTIMIZE, "updatePropositions"),
    "Optimize Get Propositions Request": api(OPTIMIZE, "getPropositions"),
    "Optimize Response": api(OPTIMIZE, "getPropositions"),
    "Optimize Update Propositions Complete": api(OPTIMIZE, "updatePropositions"),
    "Optimize Clear Propositions Request": api(OPTIMIZE, "clearCachedPropositions"),
    "Optimize Track Propositions Request": api(OPTIMIZE, "displayed", "tapped"),
    "requestgetnearbyplaces": api(PLACES, "getNearbyPointsOfInterest"),
    "responsegetnearbyplaces": api(PLACES, "getNearbyPointsOfInterest"),
    "requestgetuserwithinplaces": api(PLACES, "getCurrentPointsOfInterest"),
    "responsegetuserwithinplaces": api(PLACES, "getCurrentPointsOfInterest"),
    "requestgetlastknownlocation": api(PLACES, "getLastKnownLocation"),
    "responsegetlastknownlocation": api(PLACES, "getLastKnownLocation"),
    "requestprocessregionevent": api(PLACES, "processGeofence", "processGeofenceEvent"),
    "responseprocessregionevent": api(PLACES, "processGeofence", "processGeofenceEvent"),
    "requestreset": api(PLACES, "clear"),
    "requestsetauthorizationstatus": api(PLACES, "setAuthorizationStatus"),
    "Flags Get Feature Request": api(FLAGS, "getFeature"),
    "Flags Is Feature Enabled Request": api(FLAGS, "isFeatureEnabled"),
    "Flags Response": api(FLAGS, "getFeature", "isFeatureEnabled"),
    "Offer Decisioning Request": api(OFFER, "prefetchOffers", "retrievePrefetchedOffers"),
    "Offer Decisioning Response": api(OFFER, "retrievePrefetchedOffers"),
    "UserProfileUpdate": api(PROFILE, "updateUserAttributes"),
    "RemoveUserProfile": api(PROFILE, "removeUserAttributes"),
    "getUserAttributes": api(PROFILE, "getUserAttributes"),
    "UserProfile Response Event": api(PROFILE, "updateUserAttributes", "removeUserAttributes", "getUserAttributes"),
}
CALL_SOURCES = {
    "requestcontent", "requestidentity", "requestreset", "requestprofile", "updateidentity",
    "removeidentity", "updateconsent", "requestoperationaldata", "createtracker", "trackmedia",
    "media.requesttracker", "media.trackmedia", "os",
}
RESPONSE_SOURCES = {
    "responsecontent", "responseidentity", "responseprofile", "contentcomplete",
    "errorresponsecontent", "resetcomplete", "media.sessioncreated",
}


def events(value):
    if isinstance(value, dict):
        if {"eventName", "eventType", "eventSource", "eventData"} <= value.keys():
            yield value
        for child in value.values():
            yield from events(child)
    elif isinstance(value, list):
        for child in value:
            yield from events(child)


def signatures(text):
    result, dynamic = {}, set()
    literal = re.compile(r"[A-Za-z0-9_.:-]{1,160}")
    for block in re.findall(r"```json[ \t]*\n(.*?)\n```", text, re.S):
        for event in events(json.loads(block)):
            name, typ, source = (event[k] for k in ("eventName", "eventType", "eventSource"))
            if not all(isinstance(s, str) for s in (name, typ, source)):
                raise ValueError("Non-string signature")
            if ("<" in name or not literal.fullmatch(typ) or not literal.fullmatch(source)):
                dynamic.add((name, typ, source))
                continue
            if not name.strip() or len(name) > 256 or not typ.lower().startswith("com.adobe.eventtype."):
                raise ValueError("Invalid literal signature")
            key = name, typ.lower(), source.lower()
            candidate = name, typ, source
            result[key] = min(result.get(key, candidate), candidate)
    return sorted(result.values()), sorted(dynamic)


def build(text):
    sigs, dynamic = signatures(text)
    present = {row[0] for row in sigs}
    if set(ASSOCIATIONS) - present:
        raise ValueError("Associations absent from document: " + repr(set(ASSOCIATIONS) - present))
    mappings = []
    for name, typ, source in sigs:
        extension, names = ASSOCIATIONS.get(name, (HUB, []))
        suffix = source.lower().removeprefix("com.adobe.eventsource.")
        category = "WILD_EVENT"
        if len(names) == 1 and suffix in CALL_SOURCES:
            category = "API_CALL"
        elif len(names) == 1 and suffix in RESPONSE_SOURCES:
            category = "API_RESPONSE"
        if category == "WILD_EVENT" and len(names) == 1:
            names = []
        row = dict(eventName=name, eventType=typ, eventSource=source,
                   extension=extension, category=category)
        if len(names) == 1:
            row["apiName"] = names[0]
        elif names:
            row["apiNames"] = names
        mappings.append(row)
    catalog = dict(schemaVersion=2, catalogVersion=2, mappings=mappings)
    output = json.dumps(catalog, indent=2, ensure_ascii=True) + "\n"
    if len(mappings) > 4096 or len(output) > 512 * 1024:
        raise ValueError("Runtime catalog bounds exceeded")
    provenance = {
        "mappingDocument": "TELEMETRY_EXTENSION_EVENT_MAPPING.md",
        "mappingSha256": hashlib.sha256(text.encode()).hexdigest(),
        "rules": len(mappings),
        "categories": dict(collections.Counter(row["category"] for row in mappings)),
        "dynamicSignaturesExcluded": [list(row) for row in dynamic],
        "policy": "Exact name; normalized type/source guards. Reviewed operation associations "
                  "are observational, not proof of public caller origin or success. "
                  "Internal child/notification signatures never become API calls merely "
                  "because their source is requestContent. No eventData or ID inference.",
    }
    return output, json.dumps(provenance, indent=2) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mapping", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    asset, provenance = build(args.mapping.read_text())
    for path, content in [(ASSET, asset), (PROVENANCE, provenance)]:
        if args.check:
            if not path.exists() or path.read_text() != content:
                raise SystemExit(f"Out-of-date projection: {path}")
        else:
            path.write_text(content)
    print(("Verified" if args.check else "Generated") + " " + str(ASSET))


if __name__ == "__main__":
    main()
