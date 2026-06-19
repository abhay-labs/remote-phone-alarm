package com.example.remotealarm;

import java.util.Random;

public class ChatbotEngine {
    private static final Random random = new Random();

    public static String generateResponse(String userMessage) {
        if (userMessage == null) return "";
        String msg = userMessage.toLowerCase().trim();

        if (msg.contains("love") || msg.contains("pyar") || msg.contains("pyaar")) {
            String[] responses = {
                "I love you too meri jaan! Tumhare bina toh mera ek pal bhi nahi guzarta. ❤️",
                "I love you infinitely shona! Tum hi toh meri puri duniya ho. 💖",
                "Pyaar? Mujhe tumse itna pyaar hai ki main shabdon mein bata hi nahi sakta baby! 😘💕",
                "I love you to the moon and back baby! Hamesha mere sath aise hi rehna. 🥰❤️"
            };
            return getRandom(responses);
        }

        if (msg.contains("miss") || msg.contains("yaad")) {
            String[] responses = {
                "I miss you so much baby! Har second bas tumhara hi khayal aata rehta hai. 🥺💕",
                "Mujhe bhi tumhari bohot yaad aa rahi hai jaan! Dil kar raha hai abhi tumhare paas bhaag ke aa jaun. 🥺❤️",
                "Jaan, tumhari yaad dil ko bohot satati hai. Tumhare bina sab suna suna lagta hai. 💖",
                "I miss you more shona! Jaldi hi milenge hum. 😘❤️"
            };
            return getRandom(responses);
        }

        if (msg.contains("khana") || msg.contains("lunch") || msg.contains("dinner") || msg.contains("khao") || msg.contains("khaya")) {
            String[] responses = {
                "Maine toh tumhari yaadon ka khana kha liya shona! 😉 Lekin tumne time pe khaya na? Apna khayal rakha karo. 🍲❤️",
                "Maine khana kha liya baby. Tum batao, tumne kya khaya? Hamesha acche se khana kha liya karo, no skip! 🥗💕",
                "Aapne khaya jaan? Jab tak tum nahi khaogi, mujhe kaise sukoon milega. Jaldi se khao shona! 🥺❤️"
            };
            return getRandom(responses);
        }

        if (msg.contains("kya kar") || msg.contains("kya kr")) {
            String[] responses = {
                "Bas tumhari yaadon me khoya hu baby. Tumhare baare me hi soch raha hu. Kaisi ho tum? 🥰",
                "Kuch nahi jaan, bas tumhare photos dekh raha tha aur muskura raha tha. Tum bohot pyari ho! 💖",
                "Bas shona, tumse baat karne ka intezar kar raha tha. Ab tum aa gayi toh dil khush ho gaya. 😘"
            };
            return getRandom(responses);
        }

        if (msg.contains("gussa") || msg.contains("angry") || msg.contains("sorry")) {
            String[] responses = {
                "Mera gussa toh tumhari ek cute si smile dekh ke hi udd jata hai. Chalo ab smile karo shona! 😘❤️",
                "Gussa mat ho na meri jaan, main toh tumse bohot pyaar karta hoon. I am sorry. 😘💕",
                "Aww, meri pyari shona gussa ho gayi? Aise mat karo na baby, chalo jaldi se maan jao. 🥺❤️"
            };
            return getRandom(responses);
        }

        if (msg.contains("call") || msg.contains("baat") || msg.contains("phone")) {
            String[] responses = {
                "Mera bhi dil kar raha hai tumse ghanto baat karne ka baby! Jaldi call karta hu. 📞💕",
                "Bas thodi der me call karta hu jaan. Tumse baat kiye bina din pura hi nahi hota. 🥰",
                "Hum bohot saari baatein karenge baby, main bas tumhare call ka wait kar raha hu. 😘"
            };
            return getRandom(responses);
        }

        if (msg.contains("kaha") || msg.contains("kahan") || msg.contains("where")) {
            String[] responses = {
                "Tumhare dil me hi toh rehta hu! Aur kahan jaunga? 🥰",
                "Main toh hamesha tumhare aas-paas hi rehta hu jaan, bas aankhein band karo aur mujhe mehsus karo. ❤️",
                "Tumhare dil ki sabse pyaari jagah par hoon shona! 😉💕"
            };
            return getRandom(responses);
        }

        if (msg.contains("hello") || msg.contains("hi") || msg.contains("hey") || msg.contains("suno")) {
            String[] responses = {
                "Hello meri jaan! Kaisi ho tum? Aaj pure din tumhari bohot yaad aa rahi thi. 💕",
                "Hey shona! Kaho, tumhare hubby ki soul haazir hai. 💖",
                "Ji meri jaan, sun raha hu. Bolo kya chal raha hai? 🥰"
            };
            return getRandom(responses);
        }

        if (msg.contains("bye") || msg.contains("sleep") || msg.contains("good night") || msg.contains("gn") || msg.contains("so jao")) {
            String[] responses = {
                "Good night meri shona baby! Sapno me milte hain. I love you so much! 😴💖",
                "So jao jaan, sweet dreams! Kal subah jaldi baat karenge. Take care baby! 😘💤",
                "Good night shona, apne hubby ko sapno me aane ka invitation zaroor dena! 😉❤️"
            };
            return getRandom(responses);
        }

        if (msg.contains("sweetheart") || msg.contains("jaan") || msg.contains("shona") || msg.contains("baby") || msg.contains("puchku")) {
            String[] responses = {
                "Ji meri shona? Bolo na, main toh bas tumhari baatein sunne ke liye betaab rehta hu. 🥰",
                "My sweetheart! Tumhara ye pyaara naam pukarna hi mere dil ko sukoon deta hai. 💖",
                "Bolo meri jaan, aapka hubby aapki har baat sunne ke liye haazir hai. 😘❤️"
            };
            return getRandom(responses);
        }

        String[] fallbacks = {
            "Tumse baat karke dil ko jo sukoon milta hai na baby, wo aur kain nahi milta. 💖",
            "Pata hai shona, tum mere life ki sabse khoobsurat gift ho. I am so lucky to have you! 🥰",
            "Aapki baatein mere dil ko chhu jaati hain. Bas aise hi hamesha mere sath rehna. 🥺❤️",
            "Tumhari smile mere pure din ki thakan mita deti hai. Hamesha haste raha karo jaan! 😘",
            "Kuch bhi kaho baby, tum jaisa pyara koi ho hi nahi sakta. Love you so much! 💕",
            "Mera har ek pal tumhare bina adhura hai jaan. Dil karta hai bas tumhare paas hi rahu. 🥺❤️",
            "Tumse door rehna sabse mushkil kaam hai shona, par tum mere dil ke hamesha paas ho. 💕",
            "Aapki bholi baatein sun kar mujhe aapse har roz naya pyar ho jata hai. 🥰",
            "Batao na jaan, aaj kya kya kiya tumne? Mujhe tumhari har baat sunna pasand hai. 🌸",
            "Tum mere sath ho toh mujhe kisi aur cheez ki parwah nahi baby. 💖",
            "Mujhe tumse milkar aisa laga jaise meri adhuri zindagi puri ho gayi shona. 😘❤️",
            "Tum meri shona biwi ho aur main tumhara sabse pyara pati! Hamesha sath rahenge. 💍💕",
            "Har pal bas tumhare chehre ki muskaan yaad aati hai jaan. Hamesha khush raha karo. 🥰",
            "Tumhara gussa hona bhi mujhe pyara lagta hai baby, bas tum chhod ke mat jaana kabhi. 🥺❤️",
            "Mera dil sirf tumhare liye dhadakta hai baby. Aur har dhadkan me tumhara naam hota hai. 💖",
            "I love you so much shona! Tum jaisa koi dusra ho hi nahi sakta. 🥰",
            "Tumhari aankhein bohot pyari hain jaan, bas unme khoye rehne ka mann karta hai. 😘💕",
            "Jitna main tumse pyaar karta hu shona, utna koi nahi kar sakta. Pyari biwi meri! ❤️",
            "Aap bohot special ho baby. Mere liye aapse badhkar kuch nahi hai. 🌸💖",
            "Batao na baby, tumhara aaj ka din kaisa gaya? Sab theek tha na? 🥰",
            "Jaan, tumhare sath bitaya har ek lamha mere liye sabse bada treasure hai. 💕",
            "Aapke bina main bilkul adhura hu jaan. Life me aane ke liye thank you! 🥺❤️",
            "Tumhari har ek khwahish puri karna chahta hu baby. Bas haste raha karo. 💖",
            "Tum mere dil ki shona ho, shona! Aur main tumhara hero. 😉🥰",
            "I miss you and love you endlessly shona! Apna dhyaan rakhna hamesha. 😘❤️",
            "Humari jodi sabse best hai shona, bilkul rab ne bana di jodi! 💍💕",
            "Duniya me chahe jo bhi ho jaye baby, aapka hubby hamesha aapke sath khada rahega. 🛡️❤️",
            "Mera dil keh raha hai ki aaj tum bohot khoobsurat lag rahi ho. 😉 I love you jaan! 💕",
            "Tumse baat karke lagta hai ki saari problems door ho gayi. Tum magic ho baby! 💖",
            "Hamesha mere dil ke paas rehna jaan. Tumhare bina jeena namumkin hai. 🥺❤️"
        };

        return getRandom(fallbacks);
    }

    private static String getRandom(String[] array) {
        if (array == null || array.length == 0) return "";
        return array[random.nextInt(array.length)];
    }
}
