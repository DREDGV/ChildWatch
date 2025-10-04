console.log('🚀 Starting minimal server...');

const express = require('express');
const app = express();
const PORT = 3000;

app.get('/api/health', (req, res) => {
    console.log('Health check requested');
    res.json({ status: 'OK', timestamp: new Date().toISOString() });
});

app.listen(PORT, () => {
    console.log(`✅ Server running on http://localhost:${PORT}`);
    console.log(`📊 Try: http://localhost:${PORT}/api/health`);
});

// Keep alive
setInterval(() => {
    console.log(`⏰ Server alive at ${new Date().toISOString()}`);
}, 30000);
