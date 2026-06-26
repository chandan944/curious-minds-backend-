const fs = require('fs');
const path = require('path');

function processDir(dir) {
    const files = fs.readdirSync(dir);
    for (const file of files) {
        const fullPath = path.join(dir, file);
        if (fs.statSync(fullPath).isDirectory()) {
            processDir(fullPath);
        } else if (fullPath.endsWith('.java')) {
            let content = fs.readFileSync(fullPath, 'utf8');
            let modified = false;

            if (content.includes('LocalDateTime')) {
                // Replace import
                content = content.replace(/import java\.time\.LocalDateTime;/g, 'import java.time.Instant;');
                // Replace occurrences
                content = content.replace(/LocalDateTime\.now\(\)/g, 'Instant.now()');
                content = content.replace(/LocalDateTime/g, 'Instant');
                modified = true;
            }

            // PostgreSQl TIMESTAMPTZ fix
            // Match `private Instant <name>;` and inject @Column
            const instantRegex = /(?:@Column\((.*?)\)\s*)?(?:@(?:CreatedDate|LastModifiedDate)\s*)?(?:private Instant \w+;)/g;
            
            content = content.replace(instantRegex, (match, columnArgs) => {
                let newMatch = match;
                if (!match.includes('columnDefinition')) {
                    if (columnArgs !== undefined) {
                        // existing @Column
                        const newArgs = columnArgs.trim().length > 0 
                            ? columnArgs + ', columnDefinition = "TIMESTAMPTZ"'
                            : 'columnDefinition = "TIMESTAMPTZ"';
                        newMatch = match.replace(`@Column(${columnArgs})`, `@Column(${newArgs})`);
                    } else {
                        // no @Column
                        newMatch = `@Column(columnDefinition = "TIMESTAMPTZ")\n    ` + match.trimLeft();
                    }
                    modified = true;
                }
                return newMatch;
            });

            if (modified) {
                fs.writeFileSync(fullPath, content);
                console.log('Modified: ' + fullPath);
            }
        }
    }
}

processDir('c:/Users/lenovo/Desktop/curious-brain/DSA/src/main/java');
console.log('Done');
