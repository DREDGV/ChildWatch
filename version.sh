#!/bin/bash

# ChildWatch Version Management Script
# Автоматическое управление версиями и коммитами

set -e

# Цвета для вывода
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Функция для вывода сообщений
log() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Проверка наличия git
check_git() {
    if ! command -v git &> /dev/null; then
        error "Git не установлен. Установите Git и повторите попытку."
        exit 1
    fi
}

# Проверка статуса git репозитория
check_git_status() {
    if ! git rev-parse --git-dir > /dev/null 2>&1; then
        error "Текущая директория не является git репозиторием."
        exit 1
    fi
}

# Получение текущей версии из build.gradle
get_current_version() {
    local version_line=$(grep "versionName" app/build.gradle | head -1)
    local version=$(echo "$version_line" | sed 's/.*versionName "\([^"]*\)".*/\1/')
    echo "$version"
}

# Получение текущего versionCode
get_current_version_code() {
    local code_line=$(grep "versionCode" app/build.gradle | head -1)
    local code=$(echo "$code_line" | sed 's/.*versionCode \([0-9]*\).*/\1/')
    echo "$code"
}

# Обновление версии в build.gradle
update_version() {
    local new_version=$1
    local new_code=$2
    
    log "Обновление версии до $new_version ($new_code)..."
    
    # Обновление versionName
    sed -i "s/versionName \".*\"/versionName \"$new_version\"/" app/build.gradle
    
    # Обновление versionCode
    sed -i "s/versionCode [0-9]*/versionCode $new_code/" app/build.gradle
    
    success "Версия обновлена до $new_version ($new_code)"
}

# Обновление CHANGELOG.md
update_changelog() {
    local version=$1
    local date=$(date +%Y-%m-%d)
    
    log "Обновление CHANGELOG.md..."
    
    # Создание временного файла с новой записью
    cat > temp_changelog.md << EOF
# Changelog

Все значимые изменения в проекте ChildWatch будут документированы в этом файле.

Формат основан на [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
и этот проект придерживается [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Планируется
- Улучшение UI/UX для детей
- Добавление чата между родителями и детьми
- Создание ParentWatch приложения с картой
- Реализация геозон и алертов
- SOS кнопка и экстренные контакты
- Веб-интерфейс для родителей
- Шифрование данных
- Аутентификация и токены
- Тестирование и валидация

EOF

    # Добавление существующего содержимого (кроме заголовка)
    tail -n +2 CHANGELOG.md >> temp_changelog.md
    
    # Замена файла
    mv temp_changelog.md CHANGELOG.md
    
    success "CHANGELOG.md обновлен"
}

# Создание коммита с версией
create_version_commit() {
    local version=$1
    local message=$2
    
    log "Создание коммита версии $version..."
    
    # Добавление измененных файлов
    git add app/build.gradle CHANGELOG.md
    
    # Создание коммита
    git commit -m "Release v$version

$message

- Обновлена версия приложения до $version
- Обновлен CHANGELOG.md
- Готово к тестированию и релизу"
    
    success "Коммит версии $version создан"
}

# Создание тега версии
create_version_tag() {
    local version=$1
    
    log "Создание тега v$version..."
    
    git tag -a "v$version" -m "Release version $version"
    
    success "Тег v$version создан"
}

# Основная функция
main() {
    log "ChildWatch Version Management Script"
    log "===================================="
    
    # Проверки
    check_git
    check_git_status
    
    # Получение текущей версии
    local current_version=$(get_current_version)
    local current_code=$(get_current_version_code)
    
    log "Текущая версия: $current_version ($current_code)"
    
    # Запрос новой версии
    echo
    read -p "Введите новую версию (например, 1.0.1): " new_version
    
    if [[ -z "$new_version" ]]; then
        error "Версия не может быть пустой"
        exit 1
    fi
    
    # Проверка формата версии (простая проверка)
    if [[ ! "$new_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        error "Неверный формат версии. Используйте формат X.Y.Z (например, 1.0.1)"
        exit 1
    fi
    
    # Увеличение versionCode
    local new_code=$((current_code + 1))
    
    # Запрос сообщения коммита
    echo
    read -p "Введите описание изменений: " commit_message
    
    if [[ -z "$commit_message" ]]; then
        commit_message="Обновление версии до $new_version"
    fi
    
    # Подтверждение
    echo
    warning "Будет выполнено:"
    warning "- Обновление версии с $current_version до $new_version"
    warning "- Обновление versionCode с $current_code до $new_code"
    warning "- Обновление CHANGELOG.md"
    warning "- Создание коммита с тегом"
    echo
    
    read -p "Продолжить? (y/N): " confirm
    
    if [[ "$confirm" != "y" && "$confirm" != "Y" ]]; then
        log "Операция отменена"
        exit 0
    fi
    
    # Выполнение операций
    update_version "$new_version" "$new_code"
    update_changelog "$new_version"
    create_version_commit "$new_version" "$commit_message"
    create_version_tag "$new_version"
    
    echo
    success "Версия $new_version успешно создана!"
    log "Для отправки изменений выполните: git push origin main --tags"
}

# Запуск скрипта
main "$@"
