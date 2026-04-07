 Project Overview
This repository contains the source code for a complete full-stack gaming subscription platform. It is engineered to handle secure user authentication, automated payment processing, and real-time customer support within a highly optimized cloud infrastructure.

Key Features
Automated Subscription Processing: Engineered a secure payment gateway integration using the Paystack API. It utilizes automated backend webhooks and cryptographic signature verification to process transactions and update user access in real time without manual intervention.

Intelligent AI Support: Features an integrated customer support chatbot powered by the [OpenAI / Gemini] API, designed to provide real-time assistance, guide users, and resolve common inquiries instantly.

Robust Security Architecture: Implemented comprehensive user authentication and Role-Based Access Control (RBAC) utilizing Spring Security and JSON Web Tokens (JWT) to ensure secure data handling and administrative access.

Optimized Cloud Deployment: Architected a cost-efficient ($5/mo) production environment utilizing automated CI/CD pipelines. Code pushed to GitHub automatically triggers seamless, zero-downtime deployments to Vercel (Frontend) and Railway (Spring Boot Backend & MySQL).

Tech Stack
Frontend: React.js, JavaScript

Backend: Java, Spring Boot, Spring Security

Database: MySQL

Integrations: Paystack API, OpenAI API

DevOps / Hosting: GitHub Webhooks, Vercel, Railway
