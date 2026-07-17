const path = require('path');
const DatabaseManager = require('../database/DatabaseManager');

describe('DatabaseManager configuration', () => {
    const originalDbPath = process.env.CW_DB_PATH;

    afterEach(() => {
        if (originalDbPath === undefined) {
            delete process.env.CW_DB_PATH;
        } else {
            process.env.CW_DB_PATH = originalDbPath;
        }
    });

    test('uses the server database independently of process working directory', () => {
        delete process.env.CW_DB_PATH;

        const manager = new DatabaseManager();

        expect(manager.dbPath).toBe(
            path.join(__dirname, '..', 'childwatch.db')
        );
    });

    test('allows an explicit database path for deployment and tests', () => {
        process.env.CW_DB_PATH = ':memory:';

        const manager = new DatabaseManager();

        expect(manager.dbPath).toBe(':memory:');
    });
});
