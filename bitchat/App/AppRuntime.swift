import BitFoundation
import Combine
import Foundation
import SwiftUI
import UserNotifications
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

@MainActor
final class AppRuntime: ObservableObject {
    let chatViewModel: ChatViewModel
    let events = AppEventStream()
    /// Single source of truth for conversation message state and selection
    /// (docs/CONVERSATION-STORE-DESIGN.md). Owned here; the feature models
    /// and `ChatViewModel` observe and mutate it through its intent API.
    let conversations: ConversationStore
    let publicChatModel: PublicChatModel
    let privateInboxModel: PrivateInboxModel
    let privateConversationModel: PrivateConversationModel
    let verificationModel: VerificationModel
    let conversationUIModel: ConversationUIModel
    let locationChannelsModel: LocationChannelsModel
    let peerListModel: PeerListModel
    let appChromeModel: AppChromeModel
    let boardAlertsModel: BoardAlertsModel
    let sharedContentImportModel: SharedContentImportModel

    private let idBridge: NostrIdentityBridge
    private var cancellables = Set<AnyCancellable>()
    private var started = false
     
    init(
        keychain: KeychainManagerProtocol = KeychainManager.makeDefault(),
        idBridge: NostrIdentityBridge = NostrIdentityBridge(),
        sharedContentStore: SharedContentStore? = nil
    ) {
        self.idBridge = idBridge
        let conversations = ConversationStore()
        let peerIdentityStore = PeerIdentityStore()
        let locationPresenceStore = LocationPresenceStore()
        let locationManager = LocationChannelManager.shared
        self.conversations = conversations
        self.chatViewModel = ChatViewModel(
            keychain: keychain,
            idBridge: idBridge,
            identityManager: SecureIdentityStateManager(keychain),
            conversations: conversations,
            peerIdentityStore: peerIdentityStore,
            locationPresenceStore: locationPresenceStore,
            locationManager: locationManager
        )
        self.publicChatModel = PublicChatModel(conversations: conversations)
        self.privateInboxModel = PrivateInboxModel(conversations: conversations)
        self.locationChannelsModel = LocationChannelsModel(manager: locationManager)
        self.privateConversationModel = PrivateConversationModel(
            chatViewModel: self.chatViewModel,
            conversations: conversations,
            locationChannelsModel: self.locationChannelsModel,
            peerIdentityStore: peerIdentityStore
        )
        self.verificationModel = VerificationModel(
            chatViewModel: self.chatViewModel,
            privateConversationModel: self.privateConversationModel,
            peerIdentityStore: peerIdentityStore
        )
        self.conversationUIModel = ConversationUIModel(
            chatViewModel: self.chatViewModel,
            privateConversationModel: self.privateConversationModel,
            conversations: conversations
        )
        self.peerListModel = PeerListModel(
            chatViewModel: self.chatViewModel,
            conversations: conversations,
            locationChannelsModel: self.locationChannelsModel,
            peerIdentityStore: peerIdentityStore,
            locationPresenceStore: locationPresenceStore
        )
        let resolvedSharedContentStore: SharedContentStore?
        if let sharedContentStore {
            resolvedSharedContentStore = sharedContentStore
        } else if let sharedDefaults = UserDefaults(suiteName: BitchatApp.groupID) {
            resolvedSharedContentStore = SharedContentStore(defaults: sharedDefaults)
        } else {
            resolvedSharedContentStore = nil
        }
        let sharedContentImportModel = SharedContentImportModel(store: resolvedSharedContentStore)
        self.sharedContentImportModel = sharedContentImportModel
        self.appChromeModel = AppChromeModel(
            chatViewModel: self.chatViewModel,
            privateInboxModel: self.privateInboxModel,
            onPanicWipe: { sharedContentImportModel.discardAll() }
        )
        let chatViewModel = self.chatViewModel
        self.boardAlertsModel = BoardAlertsModel(
            arrivals: BoardStore.shared.postArrivals.eraseToAnyPublisher(),
            wipes: BoardStore.shared.didWipe.eraseToAnyPublisher(),
            dependencies: BoardAlertsModel.Dependencies(
                isOwnPost: { post in
                    let key = chatViewModel.meshService.noiseSigningPublicKeyData()
                    return !key.isEmpty && key == post.authorSigningKey
                },
                emitSystemLine: { content, geohash in
                    if geohash.isEmpty {
                        chatViewModel.addMeshOnlySystemMessage(content)
                    } else {
                        chatViewModel.addGeohashSystemMessage(content, geohash: geohash)
                    }
                }
            )
        )
        // MeshChat has no Internet/Nostr runtime. BLE is started by the chat bootstrapper.
        bindRuntimeObservers()
        NotificationDelegate.shared.runtime = self
    }

    func start() {
        guard !started else {
            checkForSharedContent()
            return
        }

        started = true
        NotificationDelegate.shared.runtime = self
        VerificationService.shared.configure(with: chatViewModel.meshService)
        Task(priority: .utility) { [weak self] in
            guard let self else { return }
            let nickname = await MainActor.run { self.chatViewModel.nickname }
            let npub = await MainActor.run {
                try? self.idBridge.getCurrentNostrIdentity()?.npub
            }
            await MainActor.run {
                _ = VerificationService.shared.buildMyQRString(nickname: nickname, npub: npub)
            }
        }

        // Bluetooth mesh startup is owned by ChatViewModelBootstrapper.
        checkForSharedContent()
        performMediaMaintenance()

        record(.launched)
        record(.startupCompleted)
    }

    /// Drops media that has outlived the retention window, then applies the
    /// explicit protection class to files that older builds wrote without
    /// one. Expiry runs first so the migration never touches files the
    /// sweep is about to delete. Detached because `AppRuntime` is
    /// main-actor and both passes go file by file through the media tree;
    /// best-effort, nothing at launch depends on their results.
    private func performMediaMaintenance() {
        Task.detached(priority: .utility) {
            let store = BLEIncomingFileStore()
            store.expireAgedMedia()
            store.migrateFileProtectionIfNeeded()
        }
    }

    func handleOpenURL(_ url: URL) {
        record(.openedURL(url.absoluteString))

        if url.scheme == "bitchat", url.host == "share" {
            checkForSharedContent()
        }
    }

    func handleDidBecomeActiveNotification() {
        chatViewModel.handleDidBecomeActive()
        checkForSharedContent()
    }

    #if os(macOS)
    func handleMacDidBecomeActiveNotification() {
        record(.scenePhaseChanged(.active))
        chatViewModel.handleDidBecomeActive()
        checkForSharedContent()
    }
    #endif

    #if os(iOS)
    func handleScenePhaseChange(_ newPhase: ScenePhase) {
        switch newPhase {
        case .background:
            record(.scenePhaseChanged(.background))
            // BLE lifecycle is owned by the mesh service.

        case .active:
            record(.scenePhaseChanged(.active))
            chatViewModel.handleDidBecomeActive()
            checkForSharedContent()

        case .inactive:
            record(.scenePhaseChanged(.inactive))

        @unknown default:
            break
        }
    }
    #endif

    func applicationWillTerminate() {
        record(.terminationRequested)
        chatViewModel.applicationWillTerminate()
    }

    func handleNotificationResponse(
        identifier: String,
        actionIdentifier: String = UNNotificationDefaultActionIdentifier,
        userInfo: [AnyHashable: Any]
    ) {
        if actionIdentifier == NotificationService.waveActionID {
            chatViewModel.sendMeshWave()
            return
        }

        if identifier.hasPrefix("private-"), let peerID = PeerID(str: userInfo["peerID"] as? String) {
            record(.notificationOpened(peerID: peerID))
            chatViewModel.startPrivateChat(with: peerID)
        }

        if let deepLink = userInfo["deeplink"] as? String, let url = URL(string: deepLink) {
            record(.deepLinkOpened(deepLink))
            openExternalURL(url)
        }
    }

    func presentationOptions(
        forNotificationIdentifier identifier: String,
        userInfo: [AnyHashable: Any]
    ) async -> UNNotificationPresentationOptions {
        if identifier.hasPrefix("private-"), let peerID = PeerID(str: userInfo["peerID"] as? String) {
            if conversations.selectedPrivatePeerID == peerID {
                return []
            }
            return [.banner, .sound]
        }

        if identifier.hasPrefix("geo-activity-"),
           let deepLink = userInfo["deeplink"] as? String,
           let geohash = deepLink.components(separatedBy: "/").last,
           case .location(let channel) = locationChannelsModel.selectedChannel,
           channel.geohash == geohash {
            return []
        }

        return [.banner, .sound]
    }
}

private extension AppRuntime {
    func bindRuntimeObservers() {
#if os(iOS)
        NotificationCenter.default.publisher(for: UIApplication.userDidTakeScreenshotNotification)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.handleScreenshotCaptured()
            }
            .store(in: &cancellables)
#endif
    }


    func checkForSharedContent() {
        let previousID = sharedContentImportModel.offer?.id
        guard let payload = sharedContentImportModel.refresh(
            destination: currentSharedContentDestination
        ) else { return }

        if previousID != payload.id {
            record(.sharedContentReadyForReview(payload.kind))
        }
    }

    var currentSharedContentDestination: SharedContentDestination {
        SharedContentDestination.resolve(
            selectedPrivatePeerID: privateConversationModel.selectedPeerID,
            privateDisplayName: privateConversationModel.selectedHeaderState?.displayName,
            activeChannel: locationChannelsModel.selectedChannel
        )
    }

    func handleScreenshotCaptured() {
        let isLocationChannelActive: Bool = {
            if case .location = chatViewModel.activeChannel { return true }
            return false
        }()

        switch Self.resolveScreenshotResponse(
            isLocationChannelsSheetPresented: appChromeModel.isLocationChannelsSheetPresented,
            isAppInfoPresented: appChromeModel.isAppInfoPresented,
            hasPrivateChatOpen: chatViewModel.selectedPrivateChatPeer != nil,
            isLocationChannelActive: isLocationChannelActive
        ) {
        case .warnLocally:
            appChromeModel.triggerScreenshotPrivacyWarning()
        case .ignore:
            break
        case .forwardToChat:
            chatViewModel.handleScreenshotCaptured()
        }
    }

    func openExternalURL(_ url: URL) {
        #if os(iOS)
        UIApplication.shared.open(url)
        #else
        NSWorkspace.shared.open(url)
        #endif
    }

    func record(_ event: AppEvent) {
        Task {
            await events.emit(event)
        }
    }
}

// MARK: - Screenshot routing

extension AppRuntime {
    /// What a screenshot triggers. Nothing on this table sends anything to
    /// a public channel (see `ChatLifecycleCoordinator.handleScreenshotCaptured`).
    enum ScreenshotCaptureResponse: Equatable {
        /// Show the local location-privacy alert; nothing is sent anywhere.
        case warnLocally
        /// Do nothing. App Info holds no conversation or location content.
        case ignore
        /// Hand to the chat layer: a DM notice goes to the peer when a
        /// secure session exists; public timelines stay silent. Mesh
        /// deliberately gets no local alert either — a mesh screenshot
        /// reveals no place and triggers no send, so there is nothing to
        /// warn about, and alerting on every screenshot would train people
        /// to dismiss the one alert that matters (the location one).
        case forwardToChat
    }

    /// Pure decision table so the screenshot routing is testable without a
    /// runtime.
    nonisolated static func resolveScreenshotResponse(
        isLocationChannelsSheetPresented: Bool,
        isAppInfoPresented: Bool,
        hasPrivateChatOpen: Bool,
        isLocationChannelActive: Bool
    ) -> ScreenshotCaptureResponse {
        if isLocationChannelsSheetPresented { return .warnLocally }
        if isAppInfoPresented { return .ignore }
        // A geohash timeline screenshot still reveals a place — warn the
        // person taking it, locally, with the same alert the channel sheet
        // uses.
        if !hasPrivateChatOpen, isLocationChannelActive { return .warnLocally }
        return .forwardToChat
    }
}
