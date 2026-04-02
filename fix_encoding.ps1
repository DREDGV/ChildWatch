# Fix corrupted UTF-8 text in activity_settings.xml
$filePath = "parentwatch\src\main\res\layout\activity_settings.xml"

# Read file as bytes
$bytes = [System.IO.File]::ReadAllBytes($filePath)

# Convert bytes from wrong encoding (UTF-8 interpreted as Windows-1251) to correct string
$wrongString = [System.Text.Encoding]::GetEncoding(1251).GetString($bytes)

# Now convert back to correct UTF-8
$correctString = [System.Text.Encoding]::UTF8.GetString([System.Text.Encoding]::GetEncoding(1251).GetBytes($wrongString))

# Write back
[System.IO.File]::WriteAllText($filePath, $correctString, [System.Text.Encoding]::UTF8)

Write-Host "Fixed!"
