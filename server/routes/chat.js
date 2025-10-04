const express = require('express');
const DatabaseManager = require('../database/DatabaseManager');

const router = express.Router();

/**
 * Chat API Routes
 * Handles real-time messaging between parent and child devices
 */

// Get chat messages for a device
router.get('/messages/:deviceId', async (req, res) => {
    try {
        const { deviceId } = req.params;
        const { limit = 100, offset = 0 } = req.query;
        
        const dbManager = new DatabaseManager();
        await dbManager.initialize();
        
        const messages = await dbManager.getChatMessages(deviceId, parseInt(limit), parseInt(offset));
        
        await dbManager.close();
        
        res.json({
            success: true,
            messages: messages.map(msg => ({
                id: msg.id,
                sender: msg.sender,
                message: msg.message,
                timestamp: msg.timestamp,
                isRead: msg.is_read === 1,
                createdAt: msg.created_at
            }))
        });
        
    } catch (error) {
        console.error('Get chat messages error:', error);
        res.status(500).json({
            error: 'Failed to get chat messages',
            code: 'CHAT_GET_ERROR'
        });
    }
});

// Send a chat message
router.post('/messages', async (req, res) => {
    try {
        const { deviceId, sender, message, timestamp } = req.body;
        
        // Validate input
        if (!deviceId || !sender || !message) {
            return res.status(400).json({
                error: 'Missing required fields: deviceId, sender, message',
                code: 'MISSING_FIELDS'
            });
        }
        
        if (!['parent', 'child'].includes(sender)) {
            return res.status(400).json({
                error: 'Invalid sender. Must be "parent" or "child"',
                code: 'INVALID_SENDER'
            });
        }
        
        const dbManager = new DatabaseManager();
        await dbManager.initialize();
        
        const messageData = {
            sender,
            message: message.trim(),
            timestamp: timestamp || Date.now(),
            isRead: false
        };
        
        const result = await dbManager.saveChatMessage(deviceId, messageData);
        
        // Log activity
        await dbManager.logActivity(deviceId, 'chat', {
            action: 'message_sent',
            sender,
            messageId: result.id
        }, messageData.timestamp);
        
        await dbManager.close();
        
        res.json({
            success: true,
            messageId: result.id,
            message: 'Message sent successfully'
        });
        
    } catch (error) {
        console.error('Send chat message error:', error);
        res.status(500).json({
            error: 'Failed to send message',
            code: 'CHAT_SEND_ERROR'
        });
    }
});

// Mark message as read
router.put('/messages/:messageId/read', async (req, res) => {
    try {
        const { messageId } = req.params;
        
        const dbManager = new DatabaseManager();
        await dbManager.initialize();
        
        await dbManager.markMessageAsRead(parseInt(messageId));
        
        await dbManager.close();
        
        res.json({
            success: true,
            message: 'Message marked as read'
        });
        
    } catch (error) {
        console.error('Mark message as read error:', error);
        res.status(500).json({
            error: 'Failed to mark message as read',
            code: 'CHAT_READ_ERROR'
        });
    }
});

module.exports = router;
