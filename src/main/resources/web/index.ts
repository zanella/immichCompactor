
import 'htmx.org';

console.log("HTMX and TypeScript loaded successfully!");

interface User {
    name: string;
    isAdmin: boolean;
}

function greetUser(user: User): void {
    const greetingElement = document.getElementById("greeting");

    if (greetingElement) {
        greetingElement.innerText = `Hello, ${user.name}! (Admin: ${user.isAdmin})`;
    }
}

// Execute on load
const currentUser: User = { name: "Kotlin Developer", isAdmin: true };
greetUser(currentUser);
