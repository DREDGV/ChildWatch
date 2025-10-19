#!/bin/bash
# ChildWatch Server Auto-Setup Script
# Ubuntu 22.04 LTS
# Author: Claude AI Assistant

set -e  # Exit on error

echo "=========================================="
echo "  ChildWatch Server Setup Script v1.0"
echo "  Ubuntu 22.04 LTS"
echo "=========================================="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${GREEN}[✓]${NC} $1"
}

print_error() {
    echo -e "${RED}[✗]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[!]${NC} $1"
}

# 1. System Update
print_status "Step 1/9: Updating system packages..."
apt update
apt upgrade -y
apt autoremove -y

# 2. Install essential packages
print_status "Step 2/9: Installing essential packages..."
apt install -y curl wget git build-essential software-properties-common

# 3. Install Node.js 20.x
print_status "Step 3/9: Installing Node.js 20.x..."
curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
apt install -y nodejs

# Verify Node.js installation
NODE_VERSION=$(node --version)
NPM_VERSION=$(npm --version)
print_status "Node.js installed: $NODE_VERSION"
print_status "NPM installed: $NPM_VERSION"

# 4. Install PM2
print_status "Step 4/9: Installing PM2 process manager..."
npm install -g pm2

# 5. Install Nginx
print_status "Step 5/9: Installing Nginx web server..."
apt install -y nginx

# 6. Install Certbot for SSL
print_status "Step 6/9: Installing Certbot for SSL certificates..."
apt install -y certbot python3-certbot-nginx

# 7. Configure firewall
print_status "Step 7/9: Configuring UFW firewall..."
apt install -y ufw
ufw --force reset
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp comment 'SSH'
ufw allow 80/tcp comment 'HTTP'
ufw allow 443/tcp comment 'HTTPS'
ufw allow 3000/tcp comment 'Node.js App'
ufw --force enable

# 8. Create project directory
print_status "Step 8/9: Creating project directories..."
mkdir -p /var/www/childwatch
mkdir -p /var/log/childwatch
chown -R www-data:www-data /var/www/childwatch
chmod -R 755 /var/www/childwatch

# 9. PM2 startup configuration
print_status "Step 9/9: Configuring PM2 startup..."
pm2 startup systemd -u root --hp /root

# Final status
echo ""
echo "=========================================="
echo -e "${GREEN}  ✓ Installation Complete!${NC}"
echo "=========================================="
echo ""
echo "System Information:"
echo "  • Node.js: $NODE_VERSION"
echo "  • NPM: $NPM_VERSION"
echo "  • PM2: $(pm2 --version)"
echo "  • Nginx: $(nginx -v 2>&1 | grep -oP 'nginx/\K[0-9.]+')"
echo ""
echo "Next Steps:"
echo "  1. Upload your ChildWatch backend code to /var/www/childwatch"
echo "  2. Create .env file with configuration"
echo "  3. Run: cd /var/www/childwatch && npm install"
echo "  4. Start app: pm2 start server.js --name childwatch"
echo "  5. Save PM2 config: pm2 save"
echo ""
echo "Useful Commands:"
echo "  • View logs: pm2 logs childwatch"
echo "  • Restart app: pm2 restart childwatch"
echo "  • Stop app: pm2 stop childwatch"
echo "  • Monitor: pm2 monit"
echo ""
echo "Firewall Status:"
ufw status numbered
echo ""
print_warning "Don't forget to configure Nginx reverse proxy!"
print_warning "Don't forget to setup SSL certificate with certbot!"
echo ""
