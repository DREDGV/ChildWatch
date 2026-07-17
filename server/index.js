const express = require("express");
const http = require("http");
const { Server } = require("socket.io");
const multer = require("multer");
const path = require("path");
const fs = require("fs");

// Import our custom modules
const AuthManager = require("./auth/AuthManager");
const AuthMiddleware = require("./middleware/AuthMiddleware");
const SocketAuthMiddleware = require("./middleware/SocketAuthMiddleware");
const DataValidator = require("./validators/DataValidator");
const DatabaseManager = require("./database/DatabaseManager");
const CommandManager = require("./managers/CommandManager");
const WebSocketManager = require("./managers/WebSocketManager");

// Import route modules
const chatRoutes = require("./routes/chat");
const locationRoutes = require("./routes/location");
const mediaRoutes = require("./routes/media");
const streamingRoutes = require("./routes/streaming");
const alertsRoutes = require("./routes/alerts");
const debugRoutes = require("./routes/debug");

const app = express();
const server = http.createServer(app);
const io = new Server(server, {
  cors: {
    origin: "*",
    methods: ["GET", "POST"],
    credentials: true,
  },
  pingTimeout: 60000,
  pingInterval: 25000,
  maxHttpBufferSize: 1e7, // 10MB for audio chunks
  transports: ["websocket", "polling"], // WebSocket preferred, polling fallback
});

const PORT = process.env.PORT || 3000;

// Initialize managers
const authManager = new AuthManager();
const authMiddleware = new AuthMiddleware(authManager);
const socketAuthMiddleware = new SocketAuthMiddleware(authManager, {
  required: process.env.CW_REQUIRE_WS_AUTH === "1",
});
const validator = new DataValidator();
const dbManager = new DatabaseManager();
const commandManager = new CommandManager();
const wsManager = new WebSocketManager(io, commandManager, dbManager);
wsManager.dbManager = dbManager;

// Initialize database
let isDbInitialized = false;
async function initializeDatabase() {
  try {
    await dbManager.initialize();
    isDbInitialized = true;
    console.log("✅ Database initialized successfully");
  } catch (error) {
    console.error("❌ Database initialization failed:", error);
    process.exit(1);
  }
}

// Initialize database on startup
initializeDatabase();

// Authenticate the Socket.IO handshake before feature handlers see the socket.
// Compatibility mode is the default until all installed Android clients send tokens.
io.use(socketAuthMiddleware.authenticate());

// Initialize WebSocket handlers
wsManager.initialize();

// Middleware
app.use(express.json({ limit: "10mb" }));
app.use(express.urlencoded({ extended: true, limit: "10mb" }));
app.use(express.static("public"));

// Apply security middleware
app.use(authMiddleware.securityHeaders());
app.use(authMiddleware.cors());
app.use(authMiddleware.requestLogger());
app.use(authMiddleware.errorHandler());

// Configure multer for file uploads
const storage = multer.diskStorage({
  destination: (req, file, cb) => {
    const uploadDir = "uploads";
    if (!fs.existsSync(uploadDir)) {
      fs.mkdirSync(uploadDir, { recursive: true });
    }
    cb(null, uploadDir);
  },
  filename: (req, file, cb) => {
    const timestamp = Date.now();
    const ext = path.extname(file.originalname);
    cb(null, `${file.fieldname}_${timestamp}${ext}`);
  },
});

const upload = multer({
  storage: storage,
  limits: {
    fileSize: 10 * 1024 * 1024, // 10MB limit
  },
});

function extractRecentApps(raw) {
  if (!raw || typeof raw !== "object") {
    return [];
  }

  const recentApps = Array.isArray(raw.recentApps) ? raw.recentApps : [];
  return recentApps
    .filter((item) => item && typeof item === "object")
    .map((item) => ({
      packageName: typeof item.packageName === "string" ? item.packageName : "",
      appName: typeof item.appName === "string" ? item.appName : "",
      lastUsed:
        typeof item.lastUsed === "number"
          ? item.lastUsed
          : Number.parseInt(item.lastUsed, 10) || 0,
      totalTimeInForeground:
        typeof item.totalTimeInForeground === "number"
          ? item.totalTimeInForeground
          : Number.parseInt(item.totalTimeInForeground, 10) || 0,
      isSystemApp: Boolean(item.isSystemApp),
    }))
    .filter((item) => item.appName || item.packageName)
    .sort((a, b) => b.lastUsed - a.lastUsed);
}

function enrichDeviceStatus(status) {
  if (!status) {
    return null;
  }

  const recentApps = extractRecentApps(status.raw);
  return {
    ...status,
    recentApps,
  };
}

function formatShortId(value) {
  const normalized = String(value || "").trim();
  if (!normalized) return "";
  if (normalized.length <= 16) return normalized;
  return `${normalized.slice(0, 8)}...${normalized.slice(-4)}`;
}

function looksLikeSyntheticParentDeviceId(value) {
  const normalized = String(value || "").trim();
  if (!normalized) return false;
  if (normalized.startsWith("parent_socket_")) return true;
  return /^parent_(?:\d{6,}|[A-Za-z0-9_-]{8,})$/.test(normalized);
}

function sanitizeLinkedParents(childDeviceId, linkedParents, wsManager) {
  const dedupedById = new Map();

  for (const link of linkedParents || []) {
    const parentDeviceId = String(link?.parentDeviceId || "").trim();
    if (!parentDeviceId || dedupedById.has(parentDeviceId)) {
      continue;
    }

    const hasKnownDeviceRecord = [
      link?.parentDeviceName,
      link?.parentDeviceType,
      link?.parentAppVersion,
    ].some((value) => String(value || "").trim().length > 0);
    const isOnline =
      wsManager.getConnectedParentSocketIdsForParent(
        parentDeviceId,
        childDeviceId
      ).length > 0;

    if (
      looksLikeSyntheticParentDeviceId(parentDeviceId) &&
      !hasKnownDeviceRecord &&
      !isOnline
    ) {
      continue;
    }

    dedupedById.set(parentDeviceId, link);
  }

  return Array.from(dedupedById.values());
}

function resolveChatSenderDisplayName(message) {
  const explicit = String(message?.sender_display_name || "").trim();
  if (explicit) return explicit;

  const senderRole = String(message?.sender || "").trim();
  const senderDeviceId = String(message?.sender_device_id || "").trim();
  if (senderRole === "child") {
    return senderDeviceId ? formatShortId(senderDeviceId) : "Ребенок";
  }
  if (senderRole === "parent") {
    return formatShortId(senderDeviceId) || "Родитель";
  }
  return "Неизвестно";
}

// Initialize streaming routes with managers
streamingRoutes.init(commandManager, dbManager, wsManager);
alertsRoutes.init(dbManager, wsManager);

// API Routes
app.use("/api/chat", chatRoutes);
app.use("/api/location", locationRoutes);
app.use("/api/media", mediaRoutes);
app.use("/api/streaming", streamingRoutes);
app.use("/api/debug", debugRoutes);
app.use("/api/alerts", authMiddleware.authenticate(), alertsRoutes);

// Routes

// Health check
app.get("/api/health", (req, res) => {
  res.json({
    status: "OK",
    timestamp: new Date().toISOString(),
    version: "1.0.0",
  });
});

// Device registration
app.post(
  "/api/auth/register",
  authMiddleware.validateRequest({
    body: {
      deviceId: {
        required: true,
        type: "string",
        minLength: 10,
        maxLength: 100,
      },
      deviceName: { required: true, type: "string", maxLength: 100 },
      deviceType: {
        required: true,
        type: "string",
        pattern: /^(android|ios)$/,
      },
      appVersion: {
        required: true,
        type: "string",
        pattern: /^\d+\.\d+\.\d+(?:\.\d+)?(?:[-/][A-Za-z0-9._]+)?$/,
      },
    },
  }),
  authMiddleware.rateLimit(60000, 10), // 10 requests per minute for registration
  async (req, res) => {
    try {
      const { deviceId, deviceName, deviceType, appVersion } = req.body;
      const sanitizedDeviceName = validator.sanitizeString(deviceName);

      if (!validator.validateDeviceIdFormat(deviceId)) {
        return res.status(400).json({
          error: "Invalid device ID format",
          code: "INVALID_DEVICE_ID",
        });
      }

      if (!validator.validateAppVersion(appVersion)) {
        return res.status(400).json({
          error: "Invalid app version format",
          code: "INVALID_APP_VERSION",
        });
      }

      const result = authManager.registerDevice({
        deviceId,
        deviceName: sanitizedDeviceName,
        deviceType,
        appVersion,
      });

      if (result.success) {
        try {
          await dbManager.registerDevice(deviceId, {
            device_name: sanitizedDeviceName,
            device_type: deviceType,
            app_version: appVersion,
          });
        } catch (dbError) {
          console.error("Device DB registration error:", dbError);
        }

        res.json({
          success: true,
          authToken: result.authToken,
          refreshToken: result.refreshToken,
          expiresIn: result.expiresIn,
        });
      } else {
        res.status(400).json({
          error: result.error,
          code: "REGISTRATION_FAILED",
        });
      }
    } catch (error) {
      console.error("Registration error:", error);
      res.status(500).json({
        error: "Internal server error",
        code: "REGISTRATION_ERROR",
      });
    }
  }
);

// Token refresh
app.post(
  "/api/auth/refresh",
  authMiddleware.validateRequest({
    body: {
      refreshToken: {
        required: true,
        type: "string",
        minLength: 64,
        maxLength: 64,
      },
      deviceId: {
        required: true,
        type: "string",
        minLength: 10,
        maxLength: 100,
      },
    },
  }),
  authMiddleware.rateLimit(60000, 20), // 20 requests per minute for refresh
  (req, res) => {
    try {
      const { refreshToken, deviceId } = req.body;

      // Validate device ID format
      if (!validator.validateDeviceIdFormat(deviceId)) {
        return res.status(400).json({
          error: "Invalid device ID format",
          code: "INVALID_DEVICE_ID",
        });
      }

      // Refresh token
      const result = authManager.refreshToken(refreshToken, deviceId);

      if (result.success) {
        res.json({
          success: true,
          authToken: result.authToken,
          refreshToken: result.refreshToken,
          expiresIn: result.expiresIn,
        });
      } else {
        res.status(401).json({
          error: result.error,
          code: "REFRESH_FAILED",
        });
      }
    } catch (error) {
      console.error("Token refresh error:", error);
      res.status(500).json({
        error: "Internal server error",
        code: "REFRESH_ERROR",
      });
    }
  }
);

// Token validation
app.get("/api/auth/validate", authMiddleware.authenticate(), (req, res) => {
  res.json({
    valid: true,
    deviceId: req.deviceId,
    expiresAt: req.deviceData.expiresAt,
  });
});

// Location upload (protected)
app.post(
  "/api/loc",
  authMiddleware.authenticate(),
  authMiddleware.rateLimit(60000, 120), // 120 requests per minute for location
  authMiddleware.validateRequest({
    body: {
      latitude: { required: true, type: "number", min: -90, max: 90 },
      longitude: { required: true, type: "number", min: -180, max: 180 },
      accuracy: { required: true, type: "number", min: 0, max: 1000 },
      timestamp: { required: true, type: "number", min: 0 },
    },
  }),
  async (req, res) => {
    try {
      const { latitude, longitude, accuracy, timestamp, deviceInfo } = req.body;
      const deviceId = req.deviceId;
      const registeredDevice = req.deviceData || authManager.getDeviceInfo(deviceId);

      // Validate location data
      const validation = validator.validateLocationData({
        latitude,
        longitude,
        accuracy,
        timestamp,
        deviceId,
      });

      if (!validation.isValid) {
        return res.status(400).json({
          error: "Invalid location data",
          code: "INVALID_LOCATION_DATA",
          details: validation.errors,
        });
      }

      // Check for suspicious location (too fast movement)
      const deviceInfoFromAuth = authManager.getDeviceInfo(deviceId);
      if (deviceInfoFromAuth && deviceInfoFromAuth.lastLocation) {
        const suspiciousCheck = validator.checkSuspiciousLocation(
          { latitude, longitude, timestamp },
          deviceInfoFromAuth.lastLocation
        );

        if (suspiciousCheck.suspicious) {
          authManager.updateDeviceActivity(deviceId, "suspicious", {
            type: "suspicious_location",
            description: suspiciousCheck.reason,
          });

          console.warn(
            `Suspicious location from ${deviceId}: ${suspiciousCheck.reason}`
          );
        }
      }

      // Prepare optional device status payload from reported deviceInfo
      let latestStatus = null;
      if (deviceInfo && typeof deviceInfo === "object") {
        try {
          const batteryInfo = deviceInfo.battery || {};
          const deviceDetails = deviceInfo.device || {};
          const currentAppInfo = deviceInfo.currentApp || {};

          // Extract app name and package from currentApp (if available)
          let appName = null;
          let appPackage = null;

          console.log(
            `📱 Current App Info received:`,
            JSON.stringify(currentAppInfo)
          );

          if (currentAppInfo && !currentAppInfo.error) {
            appName = currentAppInfo.appName || null;
            appPackage = currentAppInfo.packageName || null;
            console.log(`✅ App extracted: ${appName} (${appPackage})`);
          } else {
            console.log(
              `⚠️ No app data: ${
                currentAppInfo?.error || "currentApp is empty"
              }`
            );
          }

          latestStatus = {
            batteryLevel:
              typeof batteryInfo.level === "number" ? batteryInfo.level : null,
            isCharging:
              typeof batteryInfo.isCharging === "boolean"
                ? batteryInfo.isCharging
                : null,
            chargingType: batteryInfo.chargingType || null,
            temperature:
              typeof batteryInfo.temperature === "number"
                ? batteryInfo.temperature
                : null,
            voltage:
              typeof batteryInfo.voltage === "number"
                ? batteryInfo.voltage
                : null,
            health: batteryInfo.health || null,
            manufacturer: deviceDetails.manufacturer || null,
            model: deviceDetails.model || null,
            androidVersion: deviceDetails.androidVersion || null,
            sdkVersion:
              typeof deviceDetails.sdkVersion === "number"
                ? deviceDetails.sdkVersion
                : null,
            currentAppName: appName,
            currentAppPackage: appPackage,
            timestamp:
              typeof deviceInfo.timestamp === "number"
                ? deviceInfo.timestamp
                : Date.now(),
            raw: deviceInfo,
          };

          await dbManager.registerDevice(deviceId, {
            device_name:
              registeredDevice?.deviceName ||
              appName ||
              deviceDetails.model ||
              "Unknown Device",
            device_type: registeredDevice?.deviceType || "android",
            app_version:
              registeredDevice?.appVersion ||
              req.headers["user-agent"]?.replace(/^ChildWatch\//, "") ||
              "unknown",
          });

          await dbManager.saveDeviceStatus(deviceId, latestStatus);
          authManager.updateDeviceStatus(deviceId, latestStatus);
          console.log(
            `✅ Device status saved for ${deviceId}: Battery ${latestStatus.batteryLevel}%, Model: ${latestStatus.model}`
          );
        } catch (statusError) {
          console.error("❌ Failed to persist device status:", statusError);
        }
      }

      await dbManager.registerDevice(deviceId, {
        device_name:
          registeredDevice?.deviceName ||
          deviceInfo?.device?.model ||
          "Unknown Device",
        device_type: registeredDevice?.deviceType || "android",
        app_version:
          registeredDevice?.appVersion ||
          req.headers["user-agent"]?.replace(/^ChildWatch\//, "") ||
          "unknown",
      });

      // Save location to database
      await dbManager.saveLocation(deviceId, {
        latitude,
        longitude,
        accuracy,
        timestamp,
      });

      // Log activity
      await dbManager.logActivity(deviceId, {
        activity_type: "location",
        activity_data: {
          latitude,
          longitude,
          accuracy,
          batteryLevel: latestStatus?.batteryLevel ?? null,
          isCharging: latestStatus?.isCharging ?? null,
        },
        timestamp,
      });

      console.log(
        `[${new Date().toISOString()}] Location from ${deviceId}: Lat ${latitude}, Lng ${longitude}, Acc ${accuracy} at ${new Date(
          timestamp
        )}`
      );

      res.json({
        success: true,
        message: "Location received and saved",
        deviceId: deviceId,
        timestamp: Date.now(),
      });
    } catch (error) {
      console.error("Location upload error:", error);
      res.status(500).json({
        error: "Internal server error",
        code: "LOCATION_UPLOAD_ERROR",
      });
    }
  }
);

// Audio upload (protected)
app.post(
  "/api/audio",
  authMiddleware.authenticate(),
  authMiddleware.rateLimit(60000, 30), // 30 requests per minute for audio
  upload.single("audio"),
  async (req, res) => {
    try {
      if (!req.file) {
        return res.status(400).json({
          error: "No audio file provided",
          code: "NO_AUDIO_FILE",
        });
      }

      const deviceId = req.deviceId;
      const { timestamp } = req.body;

      // Validate file upload
      const fileValidation = validator.validateFileUpload(req.file, ["audio"]);
      if (!fileValidation.isValid) {
        return res.status(400).json({
          error: "Invalid audio file",
          code: "INVALID_AUDIO_FILE",
          details: fileValidation.errors,
        });
      }

      const audioTimestamp = timestamp ? parseInt(timestamp) : Date.now();

      // Save audio file metadata to database
      await dbManager.saveAudioFile(deviceId, {
        filename: req.file.filename,
        file_path: req.file.path,
        file_size: req.file.size,
        mime_type: req.file.mimetype,
        duration: null, // Could be extracted with ffprobe
        timestamp: audioTimestamp,
      });

      // Log activity
      await dbManager.logActivity(deviceId, {
        activity_type: "audio",
        activity_data: {
          filename: req.file.filename,
          size: req.file.size,
        },
        timestamp: audioTimestamp,
      });

      console.log(
        `[${new Date().toISOString()}] Audio from ${deviceId}: ${
          req.file.filename
        } (${req.file.size} bytes)`
      );

      res.json({
        success: true,
        message: "Audio received and saved",
        filename: req.file.filename,
        deviceId: deviceId,
        timestamp: Date.now(),
      });
    } catch (error) {
      console.error("Audio upload error:", error);
      res.status(500).json({
        error: "Internal server error",
        code: "AUDIO_UPLOAD_ERROR",
      });
    }
  }
);

// Photo upload (protected)
app.post(
  "/api/photo",
  authMiddleware.authenticate(),
  authMiddleware.rateLimit(60000, 20), // 20 requests per minute for photos
  upload.single("photo"),
  async (req, res) => {
    try {
      if (!req.file) {
        return res.status(400).json({
          error: "No photo file provided",
          code: "NO_PHOTO_FILE",
        });
      }

      const deviceId = req.deviceId;
      const { timestamp } = req.body;

      // Validate file upload
      const fileValidation = validator.validateFileUpload(req.file, ["image"]);
      if (!fileValidation.isValid) {
        return res.status(400).json({
          error: "Invalid photo file",
          code: "INVALID_PHOTO_FILE",
          details: fileValidation.errors,
        });
      }

      const photoTimestamp = timestamp ? parseInt(timestamp) : Date.now();

      // Save photo file metadata to database
      await dbManager.savePhotoFile(deviceId, {
        filename: req.file.filename,
        file_path: req.file.path,
        file_size: req.file.size,
        mime_type: req.file.mimetype,
        width: null, // Could be extracted with image processing
        height: null,
        timestamp: photoTimestamp,
      });

      // Log activity
      await dbManager.logActivity(deviceId, {
        activity_type: "photo",
        activity_data: {
          filename: req.file.filename,
          size: req.file.size,
        },
        timestamp: photoTimestamp,
      });

      console.log(
        `[${new Date().toISOString()}] Photo from ${deviceId}: ${
          req.file.filename
        } (${req.file.size} bytes)`
      );

      res.json({
        success: true,
        message: "Photo received and saved",
        filename: req.file.filename,
        deviceId: deviceId,
        timestamp: Date.now(),
      });
    } catch (error) {
      console.error("Photo upload error:", error);
      res.status(500).json({
        error: "Internal server error",
        code: "PHOTO_UPLOAD_ERROR",
      });
    }
  }
);

// Get device info (protected)
app.get(
  "/api/device/info",
  authMiddleware.authenticate(),
  authMiddleware.rateLimit(60000, 60), // 60 requests per minute
  (req, res) => {
    try {
      const deviceId = req.deviceId;
      const deviceInfo = authManager.getDeviceInfo(deviceId);

      if (!deviceInfo) {
        return res.status(404).json({
          error: "Device not found",
          code: "DEVICE_NOT_FOUND",
        });
      }

      res.json({
        success: true,
        device: deviceInfo,
      });
    } catch (error) {
      console.error("Get device info error:", error);
      res.status(500).json({
        error: "Internal server error",
        code: "DEVICE_INFO_ERROR",
      });
    }
  }
);

// Upload latest device status snapshot without requiring a location update (protected)
app.post(
  "/api/device/status",
  authMiddleware.authenticate(),
  authMiddleware.rateLimit(60000, 120),
  async (req, res) => {
    try {
      const deviceId = req.deviceId;
      const { deviceInfo } = req.body || {};
      const registeredDevice = req.deviceData || authManager.getDeviceInfo(deviceId);

      if (!deviceInfo || typeof deviceInfo !== "object") {
        return res.status(400).json({
          error: "deviceInfo payload is required",
          code: "INVALID_DEVICE_STATUS_PAYLOAD",
        });
      }

      const batteryInfo = deviceInfo.battery || {};
      const deviceDetails = deviceInfo.device || {};
      const currentAppInfo = deviceInfo.currentApp || {};

      const appName =
        currentAppInfo && !currentAppInfo.error
          ? currentAppInfo.appName || null
          : null;
      const appPackage =
        currentAppInfo && !currentAppInfo.error
          ? currentAppInfo.packageName || null
          : null;

      const latestStatus = {
        batteryLevel:
          typeof batteryInfo.level === "number" ? batteryInfo.level : null,
        isCharging:
          typeof batteryInfo.isCharging === "boolean"
            ? batteryInfo.isCharging
            : null,
        chargingType: batteryInfo.chargingType || null,
        temperature:
          typeof batteryInfo.temperature === "number"
            ? batteryInfo.temperature
            : null,
        voltage:
          typeof batteryInfo.voltage === "number" ? batteryInfo.voltage : null,
        health: batteryInfo.health || null,
        manufacturer: deviceDetails.manufacturer || null,
        model: deviceDetails.model || null,
        androidVersion: deviceDetails.androidVersion || null,
        sdkVersion:
          typeof deviceDetails.sdkVersion === "number"
            ? deviceDetails.sdkVersion
            : null,
        currentAppName: appName,
        currentAppPackage: appPackage,
        timestamp:
          typeof deviceInfo.timestamp === "number"
            ? deviceInfo.timestamp
            : Date.now(),
        raw: deviceInfo,
      };

      await dbManager.registerDevice(deviceId, {
        device_name:
          registeredDevice?.deviceName ||
          appName ||
          deviceDetails.model ||
          "Unknown Device",
        device_type: registeredDevice?.deviceType || "android",
        app_version:
          registeredDevice?.appVersion ||
          req.headers["user-agent"]?.replace(/^ChildWatch\//, "") ||
          "unknown",
      });
      await dbManager.saveDeviceStatus(deviceId, latestStatus);
      authManager.updateDeviceStatus(deviceId, latestStatus);

      res.json({
        success: true,
        deviceId,
        status: latestStatus,
      });
    } catch (error) {
      console.error("Upload device status error:", error);
      res.status(500).json({
        error: "Internal server error",
        code: "DEVICE_STATUS_UPLOAD_ERROR",
      });
    }
  }
);

// Get latest device status (protected)
app.post(
  "/api/relationships/link",
  authMiddleware.authenticate(),
  authMiddleware.rateLimit(60000, 30),
  async (req, res) => {
    try {
      const parseMarkerIconId = (rawValue) => {
        if (rawValue === null || rawValue === undefined || rawValue === "") {
          return null;
        }
        const parsed = Number.parseInt(rawValue, 10);
        if (!Number.isFinite(parsed) || parsed < 0 || parsed > 64) {
          return null;
        }
        return parsed;
      };
      const parentDeviceId = String(req.body.parentDeviceId || "").trim();
      const childDeviceId = String(req.body.childDeviceId || "").trim();
      const relationRole = String(req.body.relationRole || "guardian").trim() || "guardian";
      const displayName = typeof req.body.displayName === "string"
        ? req.body.displayName.trim().slice(0, 100)
        : null;
      let parentDisplayName = typeof req.body.parentDisplayName === "string"
        ? req.body.parentDisplayName.trim().slice(0, 100)
        : null;
      let childDisplayName = typeof req.body.childDisplayName === "string"
        ? req.body.childDisplayName.trim().slice(0, 100)
        : null;
      const parentMarkerIconId = parseMarkerIconId(req.body.parentMarkerIconId);
      const childMarkerIconId = parseMarkerIconId(req.body.childMarkerIconId);

      if (
        !validator.validateDeviceIdFormat(parentDeviceId) ||
        !validator.validateDeviceIdFormat(childDeviceId)
      ) {
        return res.status(400).json({
          error: "Invalid device ID format",
          code: "INVALID_DEVICE_ID",
        });
      }

      if (req.deviceId !== parentDeviceId && req.deviceId !== childDeviceId) {
        return res.status(403).json({
          error: "Authenticated device cannot create this link",
          code: "LINK_FORBIDDEN",
        });
      }

      if (!parentDisplayName && !childDisplayName && displayName) {
        if (req.deviceId === parentDeviceId) {
          childDisplayName = displayName;
        } else if (req.deviceId === childDeviceId) {
          parentDisplayName = displayName;
        }
      }

      await dbManager.upsertDeviceLink({
        parentDeviceId,
        childDeviceId,
        relationRole,
        displayName,
        parentDisplayName,
        childDisplayName,
        parentMarkerIconId,
        childMarkerIconId,
        createdBy: req.deviceId,
        isActive: true,
      });

      res.json({
        success: true,
        parentDeviceId,
        childDeviceId,
        relationRole,
        displayName,
        parentDisplayName,
        childDisplayName,
        parentMarkerIconId,
        childMarkerIconId,
      });
    } catch (error) {
      console.error("Link parent-child error:", error);
      res.status(500).json({
        error: "Internal server error",
        code: "DEVICE_LINK_ERROR",
      });
    }
  }
);

app.get(
  "/api/relationships/children/:parentDeviceId",
  authMiddleware.authenticate(),
  authMiddleware.rateLimit(60000, 60),
  async (req, res) => {
    try {
      const parentDeviceId = req.params.parentDeviceId;
      if (!validator.validateDeviceIdFormat(parentDeviceId)) {
        return res.status(400).json({
          error: "Invalid device ID format",
          code: "INVALID_DEVICE_ID",
        });
      }

      if (req.deviceId !== parentDeviceId) {
        return res.status(403).json({
          error: "Authenticated device cannot read these links",
          code: "LINKS_FORBIDDEN",
        });
      }

      const children = await dbManager.getLinkedChildren(parentDeviceId);
      res.json({
        success: true,
        parentDeviceId,
        count: children.length,
        children,
      });
    } catch (error) {
      console.error("Get linked children error:", error);
      res.status(500).json({
        error: "Internal server error",
        code: "LINKED_CHILDREN_ERROR",
      });
    }
  }
);

app.get(
  "/api/relationships/parents/:childDeviceId",
  authMiddleware.authenticate(),
  authMiddleware.rateLimit(60000, 60),
  async (req, res) => {
    try {
      const childDeviceId = req.params.childDeviceId;
      if (!validator.validateDeviceIdFormat(childDeviceId)) {
        return res.status(400).json({
          error: "Invalid device ID format",
          code: "INVALID_DEVICE_ID",
        });
      }

      const requesterIsChild = req.deviceId === childDeviceId;
      const requesterIsLinkedParent =
        !requesterIsChild &&
        (await dbManager.hasActiveDeviceLink(req.deviceId, childDeviceId));

      if (!requesterIsChild && !requesterIsLinkedParent) {
        return res.status(403).json({
          error: "Authenticated device cannot read these links",
          code: "LINKS_FORBIDDEN",
        });
      }

      const parents = sanitizeLinkedParents(
        childDeviceId,
        await dbManager.getLinkedParents(childDeviceId),
        wsManager
      );
      res.json({
        success: true,
        childDeviceId,
        count: parents.length,
        parents,
      });
    } catch (error) {
      console.error("Get linked parents error:", error);
      res.status(500).json({
        error: "Internal server error",
        code: "LINKED_PARENTS_ERROR",
      });
    }
  }
);

app.get(
  "/api/relationships/presence/:childDeviceId",
  authMiddleware.authenticate(),
  authMiddleware.rateLimit(60000, 60),
  async (req, res) => {
    try {
      const childDeviceId = req.params.childDeviceId;
      if (!validator.validateDeviceIdFormat(childDeviceId)) {
        return res.status(400).json({
          error: "Invalid device ID format",
          code: "INVALID_DEVICE_ID",
        });
      }

      const requesterIsChild = req.deviceId === childDeviceId;
      const requesterIsLinkedParent =
        !requesterIsChild &&
        (await dbManager.hasActiveDeviceLink(req.deviceId, childDeviceId));

      if (!requesterIsChild && !requesterIsLinkedParent) {
        return res.status(403).json({
          error: "Authenticated device cannot read this family context",
          code: "PRESENCE_FORBIDDEN",
        });
      }

      const linkedParents = sanitizeLinkedParents(
        childDeviceId,
        await dbManager.getLinkedParents(childDeviceId),
        wsManager
      );
      const childDisplayName =
        linkedParents
          .map((link) => String(link?.childDisplayName || "").trim())
          .find(Boolean) ||
        formatShortId(childDeviceId) ||
        "Ребенок";

      const parentsById = new Map();
      linkedParents.forEach((link) => {
        const parentDeviceId = String(link?.parentDeviceId || "").trim();
        if (!parentDeviceId || parentsById.has(parentDeviceId)) return;

        const displayName =
          String(link?.parentDisplayName || "").trim() ||
          String(link?.displayName || "").trim() ||
          String(link?.parentDeviceName || "").trim() ||
          formatShortId(parentDeviceId) ||
          "Родитель";

        parentsById.set(parentDeviceId, {
          role: "parent",
          deviceId: parentDeviceId,
          displayName,
          isOnline:
            wsManager.getConnectedParentSocketIdsForParent(
              parentDeviceId,
              childDeviceId
            ).length > 0,
        });
      });

      const child = {
        role: "child",
        deviceId: childDeviceId,
        displayName: childDisplayName,
        isOnline: wsManager.isChildConnectedById(childDeviceId),
      };
      const parents = Array.from(parentsById.values());
      const participants = [child, ...parents];

      res.json({
        success: true,
        childDeviceId,
        participants,
        onlineCount: participants.filter((item) => item.isOnline).length,
        totalCount: participants.length,
      });
    } catch (error) {
      console.error("Get family presence error:", error);
      res.status(500).json({
        error: "Internal server error",
        code: "FAMILY_PRESENCE_ERROR",
      });
    }
  }
);

app.post(
  "/api/relationships/unlink",
  authMiddleware.authenticate(),
  authMiddleware.rateLimit(60000, 30),
  async (req, res) => {
    try {
      const parentDeviceId = String(req.body.parentDeviceId || "").trim();
      const childDeviceId = String(req.body.childDeviceId || "").trim();

      if (
        !validator.validateDeviceIdFormat(parentDeviceId) ||
        !validator.validateDeviceIdFormat(childDeviceId)
      ) {
        return res.status(400).json({
          error: "Invalid device ID format",
          code: "INVALID_DEVICE_ID",
        });
      }

      if (req.deviceId !== parentDeviceId && req.deviceId !== childDeviceId) {
        return res.status(403).json({
          error: "Authenticated device cannot remove this link",
          code: "UNLINK_FORBIDDEN",
        });
      }

      await dbManager.deactivateDeviceLink(parentDeviceId, childDeviceId);

      res.json({
        success: true,
        parentDeviceId,
        childDeviceId,
      });
    } catch (error) {
      console.error("Unlink parent-child error:", error);
      res.status(500).json({
        error: "Internal server error",
        code: "DEVICE_UNLINK_ERROR",
      });
    }
  }
);

app.get(
  "/api/device/status/history/:deviceId",
  authMiddleware.authenticate(),
  authMiddleware.rateLimit(60000, 60),
  async (req, res) => {
    try {
      const targetDeviceId = req.params.deviceId || req.deviceId;
      const limit = Math.min(
        Math.max(Number.parseInt(req.query.limit, 10) || 60, 1),
        200
      );

      if (!validator.validateDeviceIdFormat(targetDeviceId)) {
        return res.status(400).json({
          error: "Invalid device ID format",
          code: "INVALID_DEVICE_ID",
        });
      }

      const statuses = await dbManager.getDeviceStatusHistory(targetDeviceId, limit);

      res.json({
        success: true,
        deviceId: targetDeviceId,
        count: statuses.length,
        statuses: statuses.map((status) => enrichDeviceStatus(status)),
      });
    } catch (error) {
      console.error("Get device status history error:", error);
      res.status(500).json({
        error: "Internal server error",
        code: "DEVICE_STATUS_HISTORY_ERROR",
      });
    }
  }
);

app.get(
  "/api/device/status/:deviceId?",
  authMiddleware.authenticate(),
  authMiddleware.rateLimit(60000, 60),
  async (req, res) => {
    try {
      const targetDeviceId = req.params.deviceId || req.deviceId;

      if (!validator.validateDeviceIdFormat(targetDeviceId)) {
        return res.status(400).json({
          error: "Invalid device ID format",
          code: "INVALID_DEVICE_ID",
        });
      }

      let status = await dbManager.getLatestDeviceStatus(targetDeviceId);
      if (!status) {
        status = authManager.getDeviceStatus(targetDeviceId);
      }

      if (status && status.raw === undefined) {
        // Ensure raw property is always present (even if null)
        status.raw = null;
      }

      res.json({
        success: true,
        deviceId: targetDeviceId,
        status: enrichDeviceStatus(status),
      });
    } catch (error) {
      console.error("Get device status error:", error);
      res.status(500).json({
        error: "Internal server error",
        code: "DEVICE_STATUS_ERROR",
      });
    }
  }
);

// Get latest location of a device (protected)
app.get(
  "/api/location/latest/:deviceId?",
  authMiddleware.authenticate(),
  authMiddleware.rateLimit(60000, 120), // 120 requests per minute
  async (req, res) => {
    try {
      // If deviceId is provided in params, use it; otherwise use authenticated device's own location
      const targetDeviceId = req.params.deviceId || req.deviceId;

      // Validate device ID
      if (!validator.validateDeviceIdFormat(targetDeviceId)) {
        return res.status(400).json({
          error: "Invalid device ID format",
          code: "INVALID_DEVICE_ID",
        });
      }

      // Get latest location from database
      const location = await dbManager.getLatestLocation(targetDeviceId);

      if (!location) {
        return res.status(404).json({
          error: "No location data found for this device",
          code: "LOCATION_NOT_FOUND",
          deviceId: targetDeviceId,
        });
      }

      res.json({
        success: true,
        deviceId: targetDeviceId,
        location: {
          latitude: location.latitude,
          longitude: location.longitude,
          accuracy: location.accuracy,
          timestamp: location.timestamp,
          recordedAt: new Date(location.timestamp).toISOString(),
        },
      });
    } catch (error) {
      console.error("Get latest location error:", error);
      res.status(500).json({
        error: "Internal server error",
        code: "LOCATION_FETCH_ERROR",
      });
    }
  }
);

// Get location history of a device (protected)
app.get(
  "/api/location/history/:deviceId?",
  authMiddleware.authenticate(),
  authMiddleware.rateLimit(60000, 60), // 60 requests per minute
  async (req, res) => {
    try {
      const targetDeviceId = req.params.deviceId || req.deviceId;
      const limit = parseInt(req.query.limit) || 100;
      const offset = parseInt(req.query.offset) || 0;
      const from =
        req.query.from !== undefined ? parseInt(req.query.from, 10) : null;
      const to = req.query.to !== undefined ? parseInt(req.query.to, 10) : null;

      // Validate parameters
      if (!validator.validateDeviceIdFormat(targetDeviceId)) {
        return res.status(400).json({
          error: "Invalid device ID format",
          code: "INVALID_DEVICE_ID",
        });
      }

      if (limit < 1 || limit > 1000) {
        return res.status(400).json({
          error: "Limit must be between 1 and 1000",
          code: "INVALID_LIMIT",
        });
      }

      if (from !== null && Number.isNaN(from)) {
        return res.status(400).json({
          error: "Invalid from timestamp",
          code: "INVALID_FROM_TIMESTAMP",
        });
      }

      if (to !== null && Number.isNaN(to)) {
        return res.status(400).json({
          error: "Invalid to timestamp",
          code: "INVALID_TO_TIMESTAMP",
        });
      }

      // Get location history from database
      const locations = await dbManager.getLocationHistory(
        targetDeviceId,
        limit,
        offset,
        from,
        to
      );

      res.json({
        success: true,
        deviceId: targetDeviceId,
        count: locations.length,
        limit: limit,
        offset: offset,
        locations: locations.map((loc) => ({
          latitude: loc.latitude,
          longitude: loc.longitude,
          accuracy: loc.accuracy,
          timestamp: loc.timestamp,
          recordedAt: new Date(loc.timestamp).toISOString(),
        })),
      });
    } catch (error) {
      console.error("Get location history error:", error);
      res.status(500).json({
        error: "Internal server error",
        code: "LOCATION_HISTORY_ERROR",
      });
    }
  }
);

// Get chat message history (protected)
app.get(
  "/api/chat/history/:deviceId?",
  authMiddleware.authenticate(),
  authMiddleware.rateLimit(60000, 60), // 60 requests per minute
  async (req, res) => {
    try {
      const targetDeviceId = req.params.deviceId || req.deviceId;
      const limit = parseInt(req.query.limit) || 100;

      // Validate parameters
      if (!validator.validateDeviceIdFormat(targetDeviceId)) {
        return res.status(400).json({
          error: "Invalid device ID format",
          code: "INVALID_DEVICE_ID",
        });
      }

      if (limit < 1 || limit > 500) {
        return res.status(400).json({
          error: "Limit must be between 1 and 500",
          code: "INVALID_LIMIT",
        });
      }

      // Get chat messages from database
      const messages = await dbManager.getChatMessages(targetDeviceId, limit);

      res.json({
        success: true,
        deviceId: targetDeviceId,
        count: messages.length,
        messages: messages.map((msg) => ({
          id: String(msg.client_id || msg.client_message_id || msg.id),
          sender: msg.sender,
          senderRole: msg.sender,
          senderDeviceId: msg.sender_device_id || "",
          senderDisplayName: resolveChatSenderDisplayName(msg),
          message: msg.message,
          timestamp: msg.timestamp,
          isRead: msg.is_read === 1,
          createdAt: new Date(msg.created_at * 1000).toISOString(),
        })),
      });
    } catch (error) {
      console.error("Get chat history error:", error);
      res.status(500).json({
        error: "Internal server error",
        code: "CHAT_HISTORY_ERROR",
      });
    }
  }
);

// Mark chat messages as read (protected)
app.post(
  "/api/chat/mark-read/:deviceId?",
  authMiddleware.authenticate(),
  authMiddleware.rateLimit(60000, 30), // 30 requests per minute
  async (req, res) => {
    try {
      const targetDeviceId = req.params.deviceId || req.deviceId;

      // Validate device ID
      if (!validator.validateDeviceIdFormat(targetDeviceId)) {
        return res.status(400).json({
          error: "Invalid device ID format",
          code: "INVALID_DEVICE_ID",
        });
      }

      // Mark messages as read in database
      await dbManager.markMessagesAsRead(targetDeviceId);

      res.json({
        success: true,
        deviceId: targetDeviceId,
        message: "Messages marked as read",
      });
    } catch (error) {
      console.error("Mark messages as read error:", error);
      res.status(500).json({
        error: "Internal server error",
        code: "MARK_READ_ERROR",
      });
    }
  }
);

// Get server statistics (protected)
app.get(
  "/api/admin/stats",
  authMiddleware.authenticate(),
  authMiddleware.rateLimit(60000, 10), // 10 requests per minute
  (req, res) => {
    try {
      const deviceId = req.deviceId;

      // Check if device has admin permissions
      if (!authManager.checkDevicePermissions(deviceId, "admin")) {
        return res.status(403).json({
          error: "Admin access required",
          code: "ADMIN_ACCESS_REQUIRED",
        });
      }

      const authStats = authManager.getAuthStats();
      const validatorStats = {
        rateLimitEntries: validator.rateLimitMap.size,
        maxRequestsPerMinute: validator.maxRequestsPerMinute,
        maxRequestsPerHour: validator.maxRequestsPerHour,
      };

      res.json({
        success: true,
        stats: {
          auth: authStats,
          validator: validatorStats,
          server: {
            uptime: process.uptime(),
            memory: process.memoryUsage(),
            version: "1.0.0",
          },
        },
      });
    } catch (error) {
      console.error("Get stats error:", error);
      res.status(500).json({
        error: "Internal server error",
        code: "STATS_ERROR",
      });
    }
  }
);

// Revoke device access (protected)
app.post(
  "/api/admin/revoke",
  authMiddleware.authenticate(),
  authMiddleware.rateLimit(60000, 5), // 5 requests per minute
  authMiddleware.validateRequest({
    body: {
      targetDeviceId: {
        required: true,
        type: "string",
        minLength: 10,
        maxLength: 100,
      },
    },
  }),
  (req, res) => {
    try {
      const adminDeviceId = req.deviceId;
      const { targetDeviceId } = req.body;

      // Check if device has admin permissions
      if (!authManager.checkDevicePermissions(adminDeviceId, "admin")) {
        return res.status(403).json({
          error: "Admin access required",
          code: "ADMIN_ACCESS_REQUIRED",
        });
      }

      const success = authManager.revokeDeviceAccess(targetDeviceId);

      if (success) {
        res.json({
          success: true,
          message: "Device access revoked",
          deviceId: targetDeviceId,
        });
      } else {
        res.status(404).json({
          error: "Device not found",
          code: "DEVICE_NOT_FOUND",
        });
      }
    } catch (error) {
      console.error("Revoke device error:", error);
      res.status(500).json({
        error: "Internal server error",
        code: "REVOKE_ERROR",
      });
    }
  }
);

// 404 handler
app.use(authMiddleware.notFoundHandler());

// Cleanup interval for streaming sessions
setInterval(() => {
  commandManager.cleanup();
}, 60000); // Every minute

// Start server (use server.listen instead of app.listen for Socket.IO)
server.listen(PORT, "0.0.0.0", () => {
  console.log(`ChildWatch Server running on port ${PORT}`);
  console.log(`Health check: http://localhost:${PORT}/api/health`);
  console.log(
    `Register device: POST http://localhost:${PORT}/api/auth/register`
  );
  console.log(
    `Audio streaming: POST http://localhost:${PORT}/api/streaming/start`
  );
  console.log(`WebSocket: ws://localhost:${PORT}`);
  console.log(`Server version: 1.2.0 (WebSocket enabled)`);
  console.log(`Environment: ${process.env.NODE_ENV || "development"}`);
});

module.exports = app;
